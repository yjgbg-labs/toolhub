import type { Context, Cleanup } from "@opencode/plugin/promise/plugin";
import { setTimeout as delay } from "node:timers/promises";
import type { ApiClient } from "./api";

interface Notification { id: string; targetSessionId: string; message: string; display: string; createdAt: string }
interface Watch { controller: AbortController; ready: Promise<void>; task: Promise<void> }

function notification(value: unknown, sessionId: string): Notification {
  const item = value as Partial<Notification> | null;
  if (!item || typeof item.id !== "string" || !/^[a-f0-9-]{36}$/.test(item.id) ||
      item.targetSessionId !== sessionId || typeof item.message !== "string" ||
      typeof item.display !== "string" || typeof item.createdAt !== "string") throw new Error("Invalid notification event");
  return item as Notification;
}

/** Location-scoped subscriptions survive idle sessions and are restored when the plugin reloads. */
export async function startNotifications(ctx: Context, client: ApiClient): Promise<Cleanup> {
  if (!client.catalog.streams.has("notify.subscribe")) return () => {};
  const prefix = `notify/session/${encodeURIComponent(ctx.location.directory)}/${encodeURIComponent(ctx.location.workspaceID ?? "")}/`;
  const controller = new AbortController();
  const watches = new Map<string, Watch>();
  const versions = new Map<string, number>();
  const tasks = new Set<Promise<unknown>>();
  const report = (error: unknown) => console.error("[groot notify]", error);
  const sameLocation = (location: { directory: string; workspaceID?: string }) =>
    location.directory === ctx.location.directory && location.workspaceID === ctx.location.workspaceID;
  const pause = (ms: number, signal: AbortSignal) => delay(ms, undefined, { signal }).catch(() => {});
  function background(task: Promise<unknown>) {
    const tracked = task.catch(error => { if (!controller.signal.aborted) report(error); });
    tasks.add(tracked);
    void tracked.finally(() => tasks.delete(tracked));
  }

  async function listen(sessionId: string, signal: AbortSignal, ready: () => void) {
    let backoff = 250;
    while (!signal.aborted) {
      const attempt = new AbortController();
      const combined = AbortSignal.any([signal, attempt.signal]);
      let timer = setTimeout(() => attempt.abort(), 5_000);
      try {
        for await (const raw of client.subscribe("notify.subscribe", { sessionId }, combined)) {
          clearTimeout(timer);
          timer = setTimeout(() => attempt.abort(), 45_000);
          const event = raw as { kind?: string; notification?: unknown } | null;
          if (event?.kind === "ready") { backoff = 250; ready(); continue; }
          if (event?.kind === "heartbeat") continue;
          if (event?.kind !== "message") throw new Error("Unexpected notification SSE event");
          const item = notification(event.notification, sessionId);
          // Stable inbox IDs also deduplicate overlapping plugin instances during location reloads.
          let retry = 250;
          while (!combined.aborted) {
            try {
              await ctx.session.synthetic({
                sessionID: sessionId, id: `msg_notify_${item.id.replaceAll("-", "")}`,
                text: item.message, description: item.display, delivery: "steer", resume: true,
                metadata: { groot: { notificationId: item.id, createdAt: item.createdAt } },
              }, { signal: combined });
              break;
            } catch (error) {
              if (combined.aborted) break;
              report(error);
              await pause(retry, combined);
              retry = Math.min(retry * 2, 5_000);
            }
          }
        }
      } catch (error) {
        if (!signal.aborted) report(error);
      } finally {
        clearTimeout(timer);
        attempt.abort();
      }
      if (!signal.aborted) await pause(backoff, signal);
      backoff = Math.min(backoff * 2, 15_000);
    }
  }

  async function forget(sessionId: string) {
    versions.set(sessionId, (versions.get(sessionId) ?? 0) + 1);
    const watch = watches.get(sessionId);
    watches.delete(sessionId);
    watch?.controller.abort();
    await watch?.task;
    await ctx.storage.remove(prefix + sessionId);
  }

  async function ensure(sessionId: string): Promise<Watch | undefined> {
    if (controller.signal.aborted) return;
    const existing = watches.get(sessionId);
    if (existing) return existing;
    const version = versions.get(sessionId);
    const session = await ctx.session.get({ sessionID: sessionId }, { signal: controller.signal });
    if (!sameLocation(session.location) || session.time.archived !== undefined) return;
    if (controller.signal.aborted || versions.get(sessionId) !== version) return;
    if (watches.has(sessionId)) return watches.get(sessionId);
    const stop = new AbortController();
    const signal = AbortSignal.any([stop.signal, controller.signal]);
    let ready!: () => void;
    const watch: Watch = { controller: stop, ready: new Promise(resolve => { ready = resolve; }), task: Promise.resolve() };
    watches.set(sessionId, watch);
    watch.task = listen(sessionId, signal, ready);
    background(ctx.storage.set(prefix + sessionId, true));
    return watch;
  }

  async function admit(event: { sessionID: string }) {
    try {
      const watch = await ensure(event.sessionID);
      if (!watch) return;
      // Ensure first-turn jobs can notify, but an unavailable Groot must not block the agent indefinitely.
      const timeout = new AbortController();
      try { await Promise.race([watch.ready, pause(5_000, AbortSignal.any([controller.signal, timeout.signal]))]); }
      finally { timeout.abort(); }
    } catch (error) { if (!controller.signal.aborted) report(error); }
  }

  const registrations: { dispose(): Promise<void> }[] = [];
  async function cleanup() {
    controller.abort();
    await Promise.allSettled(registrations.map(value => value.dispose()));
    await Promise.allSettled([...tasks, ...[...watches.values()].map(watch => watch.task)]);
    watches.clear();
  }
  try {
    registrations.push(await ctx.session.hook("prompt", admit));
    registrations.push(await ctx.session.hook("context", admit));
    background((async () => {
      for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
        try {
          if (event.type === "session.deleted") {
            await forget(event.data.sessionID);
          } else if (event.type === "session.moved") {
            await forget(event.data.sessionID);
            if (sameLocation(event.data.location)) await ensure(event.data.sessionID);
          } else if (event.type === "session.created") {
            if (sameLocation(event.data.location)) await ensure(event.data.sessionID);
          } else if (event.type === "session.viewed" || event.type === "session.execution.started") {
            if (!event.location || sameLocation(event.location)) await ensure(event.data.sessionID);
          }
        } catch (error) { if (!controller.signal.aborted) report(error); }
      }
    })());
    background((async () => {
      let after: string | undefined;
      do {
        const page = await ctx.storage.scan({ prefix, after, limit: 100 });
        for (const { key } of page.entries) {
          if (controller.signal.aborted) return;
          try { if (!await ensure(key.slice(prefix.length))) await ctx.storage.remove(key); }
          catch (error) { if (!controller.signal.aborted) report(error); }
        }
        after = page.next;
      } while (after && !controller.signal.aborted);
    })());
    return cleanup;
  } catch (error) {
    await cleanup();
    throw error;
  }
}
