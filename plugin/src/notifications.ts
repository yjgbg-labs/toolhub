import type { Context, Cleanup } from "@opencode/plugin/promise/plugin";
import { setTimeout as delay } from "node:timers/promises";
import type { ApiClient } from "./api";

interface Notification { id: string; targetSessionId: string; message: string; display: string; createdAt: string }

function notification(value: unknown): Notification {
  const item = value as Partial<Notification> | null;
  if (!item || typeof item.id !== "string" || !/^[a-f0-9-]{36}$/.test(item.id) ||
      typeof item.targetSessionId !== "string" || !/^ses[A-Za-z0-9_-]{1,125}$/.test(item.targetSessionId) ||
      typeof item.message !== "string" || typeof item.display !== "string" || typeof item.createdAt !== "string") {
    throw new Error("Invalid notification event");
  }
  return item as Notification;
}

/** One global stream per plugin instance; targetSessionId selects the receiving OpenCode session. */
export async function startNotifications(ctx: Context, client: ApiClient): Promise<Cleanup> {
  if (!client.catalog.streams.has("notify.subscribe")) return () => {};
  const controller = new AbortController();
  const report = (error: unknown) => console.error("[groot notify]", error);
  const pause = (ms: number, signal: AbortSignal) => delay(ms, undefined, { signal }).catch(() => {});
  let ready!: () => void;
  const connected = new Promise<void>(resolve => { ready = resolve; });

  async function deliver(item: Notification, signal: AbortSignal) {
    // Bound retries so one invalid/unavailable target cannot indefinitely hold up other sessions.
    for (let attempt = 0; attempt < 3 && !signal.aborted; attempt++) {
      try {
        await ctx.session.synthetic({
          sessionID: item.targetSessionId, id: `msg_notify_${item.id.replaceAll("-", "")}`,
          text: item.message, description: item.display, delivery: "steer", resume: true,
          metadata: { groot: { notificationId: item.id, createdAt: item.createdAt } },
        }, { signal });
        return;
      } catch (error) {
        if (signal.aborted) return;
        report(error);
        if (attempt < 2) await pause(250 * 2 ** attempt, signal);
      }
    }
  }

  async function listen() {
    let backoff = 250;
    while (!controller.signal.aborted) {
      const attempt = new AbortController();
      const signal = AbortSignal.any([controller.signal, attempt.signal]);
      let timer = setTimeout(() => attempt.abort(), 5_000);
      try {
        for await (const raw of client.subscribe("notify.subscribe", undefined, signal)) {
          clearTimeout(timer);
          timer = setTimeout(() => attempt.abort(), 45_000);
          const event = raw as { kind?: string; notification?: unknown } | null;
          if (event?.kind === "ready") { backoff = 250; ready(); continue; }
          if (event?.kind === "heartbeat") continue;
          try {
            if (event?.kind !== "message") throw new Error("Unexpected notification SSE event");
            await deliver(notification(event.notification), signal);
          } catch (error) { report(error); }
        }
      } catch (error) {
        if (!controller.signal.aborted) report(error);
      } finally {
        clearTimeout(timer);
        attempt.abort();
      }
      if (!controller.signal.aborted) await pause(backoff, controller.signal);
      backoff = Math.min(backoff * 2, 15_000);
    }
  }

  const task = listen();
  // Give the initial subscription time to register before the plugin's first model turn.
  const timeout = new AbortController();
  try { await Promise.race([connected, pause(5_000, timeout.signal)]); }
  finally { timeout.abort(); }
  return async () => { controller.abort(); await task; };
}
