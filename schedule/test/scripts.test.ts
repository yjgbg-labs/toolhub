import {expect, test} from "bun:test";

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const logger = {log() {}};
async function script(name: string, tools: object) {
  const source = await Bun.file(new URL(`../scripts/${name}.js`, import.meta.url)).text();
  return new AsyncFunction("tools", "console", source)(tools, logger);
}
const boot = "c3108690-4ccd-4e35-8149-bd9a3bcf0945";
function sample({uptime = 100, cpu = "100 0 50 800 50 0 0 0", rx = 1000, tx = 500, bootId = boot} = {}) {
  return `BOOT ${bootId}\nCPU cpu ${cpu}\nMEM 4000000 3000000\nTEMP 42000\nDISK 2500000 10%\nNET ${rx} ${tx} ${uptime} 3\n`;
}
function router() {
  let saved: string | null = null;
  let output = sample();
  const items: Record<string, any> = {};
  const tools = {
    "bash-on-r6s": async () => ({body: {exitCode: 0, stdout: output, stderr: ""}}),
    state: {
      get: async () => ({body: saved}),
      set: async ({value}: {value: string}) => { saved = value; },
    },
    widget: {set: async (item: any) => { items[item.key] = item; }},
  };
  return {tools, items, setOutput: (value: string) => { output = value; }};
}

test("router uses a whole scheduling interval for CPU and WAN rates", async () => {
  const {tools, items, setOutput} = router();
  await script("widget-router-metrics", tools);
  expect(items["router-summary"].value).toStartWith("--%/42C");
  expect(items["router-network"].color).toBe("yellow");
  setOutput(sample({uptime: 110, cpu: "120 0 60 870 50 0 0 0", rx: 12501000, tx: 2500500}));
  await script("widget-router-metrics", tools);
  expect(items["router-summary"].value).toStartWith("30%/42C");
  expect(items["router-network"].value).toBe("↑ 2.00 Mbps | ↓ 10.0 Mbps");
  expect(items["router-network"].color).toBe("green");
});

test("reboot, counter reset and stale router samples start a fresh window", async () => {
  for (const changed of [
    sample({uptime: 110, bootId: "d3108690-4ccd-4e35-8149-bd9a3bcf0945"}),
    sample({uptime: 110, cpu: "1 0 0 1 0 0 0 0", rx: 1, tx: 1}),
    sample({uptime: 230}),
  ]) {
    const {tools, items, setOutput} = router();
    await script("widget-router-metrics", tools);
    setOutput(changed);
    await script("widget-router-metrics", tools);
    expect(items["router-summary"].value).toStartWith("--%");
    expect(items["router-network"].value).toBe("↑ -- Mbps | ↓ -- Mbps");
  }
});

test("remote failures update widget state and reject for scheduler retry", async () => {
  for (const [name, command, keys] of [
    ["widget-router-metrics", "bash-on-r6s", ["router-summary", "router-network"]],
    ["widget-mouse-battery", "powershell", ["mouse-battery"]],
  ] as const) {
    const items: Record<string, any> = {};
    const tools = {
      [command]: async () => ({body: {exitCode: 1, stdout: "", stderr: "device offline"}}),
      widget: {set: async (item: any) => { items[item.key] = item; }},
    };
    await expect(script(name, tools)).rejects.toThrow("device offline");
    for (const key of keys) expect(items[key]).toMatchObject({value: "OFFLINE", color: "red"});
  }
});

test("mouse distinguishes charging, full and low battery reports", async () => {
  for (const [status, level, value, color] of [[3, 45, "45% CHARGING", "green"], [2, 99, "100% FULL", "green"], [1, 10, "10%", "red"]] as const) {
    let item;
    await script("widget-mouse-battery", {
      powershell: async () => ({body: {exitCode: 0, stdout: JSON.stringify({status, level, samples: 1}), stderr: ""}}),
      widget: {set: async (current: any) => { item = current; }},
    });
    expect(item).toMatchObject({key: "mouse-battery", value, color});
  }
});

test("tray propagates PowerShell errors and accepts an idempotent no-op", async () => {
  await expect(script("windows-tray-always-show", {
    powershell: async () => ({body: {exitCode: 1, stdout: "", stderr: "registry unavailable"}}),
  })).rejects.toThrow("registry unavailable");
  await script("windows-tray-always-show", {
    powershell: async () => ({body: {exitCode: 0, stdout: '{"PromotedCount":0,"AutoTrayChanged":false}', stderr: ""}}),
  });
});
