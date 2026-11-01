const system = {key: "router-summary", label: "R6S-SYS", order: 31};
const network = {key: "router-network", label: "R6S-NETWORK", order: 34};
const stateKey = "widget-router-metrics:sample";
const sample = String.raw`set -euo pipefail
export LC_ALL=C
read -r boot < /proc/sys/kernel/random/boot_id
IFS= read -r cpu < /proc/stat
mem_total= mem_available=
while read -r key value rest; do
  case "$key" in
    MemTotal:) mem_total="$value" ;;
    MemAvailable:) mem_available="$value" ;;
  esac
done < /proc/meminfo
test -n "$mem_total" && test -n "$mem_available"
read -r rx < /sys/class/net/lan2/statistics/rx_bytes
read -r tx < /sys/class/net/lan2/statistics/tx_bytes
read -r ifindex < /sys/class/net/lan2/ifindex
read -r uptime rest < /proc/uptime
read -r temperature < /sys/class/thermal/thermal_zone0/temp
set -- $(df -Pk / | tail -n 1)
printf 'BOOT %s\nCPU %s\nMEM %s %s\nTEMP %s\nDISK %s %s\nNET %s %s %s %s\n' \
  "$boot" "$cpu" "$mem_total" "$mem_available" "$temperature" "$3" "$5" "$rx" "$tx" "$uptime" "$ifindex"`;
const tone = (value, yellow, red) => value >= red ? "red" : value >= yellow ? "yellow" : "green";
function fields(output, tag) {
  const line = output.split("\n").find(line => line.startsWith(tag + " "));
  if (!line) throw Error("Missing router sample: " + tag);
  return line.trim().split(/\s+/).slice(1);
}
function mbps(bytes, seconds) {
  const value = bytes * 8 / seconds / 1000000;
  return value >= 100 ? String(Math.round(value)) : value.toFixed(value >= 10 ? 1 : 2);
}
try {
  const result = (await tools["bash-on-r6s"]({body: sample, timeout: "6s"})).body;
  if (result.exitCode !== 0) throw Error(result.stderr || "Bash on R6S exit " + result.exitCode);
  const output = result.stdout;
  const cpuFields = fields(output, "CPU"), counters = cpuFields.slice(1, 9).map(Number);
  const bootId = fields(output, "BOOT")[0];
  const [memoryTotal, memoryAvailable] = fields(output, "MEM").map(Number);
  const temperature = Number(fields(output, "TEMP")[0]) / 1000;
  const [rawUsed, rawPercent] = fields(output, "DISK");
  const diskUsed = Number(rawUsed), diskPercent = Number(rawPercent?.replace("%", ""));
  const [rx, tx, uptime, ifindex] = fields(output, "NET").map(Number);
  if (cpuFields[0] !== "cpu" || counters.length !== 8 || counters.some(n => !Number.isFinite(n) || n < 0)
      || !/^[0-9a-f-]{36}$/.test(bootId)
      || ![memoryTotal, memoryAvailable, temperature, diskUsed, diskPercent, rx, tx, uptime, ifindex].every(Number.isFinite)
      || memoryTotal <= 0 || memoryAvailable < 0 || memoryAvailable > memoryTotal || temperature <= 0
      || diskUsed < 0 || diskPercent < 0 || diskPercent > 100 || rx < 0 || tx < 0 || uptime <= 0 || ifindex <= 0)
    throw Error("Invalid router metrics");
  const current = {bootId, idle: counters[3] + counters[4], total: counters.reduce((sum, n) => sum + n, 0), rx, tx, uptime, ifindex};
  const saved = (await tools.state.get({key: stateKey})).body;
  let previous = null;
  try { previous = JSON.parse(saved); } catch { /* Start a fresh counter window. */ }
  const elapsed = uptime - previous?.uptime;
  const reusable = previous?.bootId === bootId && elapsed > 0 && elapsed <= 120;
  const total = current.total - previous?.total, idle = current.idle - previous?.idle;
  const cpu = reusable && total > 0 && idle >= 0 && idle <= total ? Math.round((total - idle) * 100 / total) : null;
  const validNetwork = reusable && previous.ifindex === ifindex && previous.rx >= 0 && previous.tx >= 0
    && rx >= previous.rx && tx >= previous.tx;
  await tools.state.set({key: stateKey, value: JSON.stringify(current)});
  const memoryUsed = memoryTotal - memoryAvailable, memoryPercent = Math.round(memoryUsed * 100 / memoryTotal);
  const tones = [cpu === null ? "yellow" : tone(cpu, 70, 90), tone(memoryPercent, 75, 90), tone(temperature, 70, 85), tone(diskPercent, 75, 90)];
  const color = tones.includes("red") ? "red" : tones.includes("yellow") ? "yellow" : "green";
  const systemValue = `${cpu === null ? "--" : cpu}%/${Math.round(temperature)}C | ${(memoryUsed/1048576).toFixed(1)}/${(memoryTotal/1048576).toFixed(1)}G | ${(diskUsed/1048576).toFixed(1)}G`;
  const networkValue = validNetwork ? `↑ ${mbps(tx-previous.tx,elapsed)} Mbps | ↓ ${mbps(rx-previous.rx,elapsed)} Mbps` : "↑ -- Mbps | ↓ -- Mbps";
  await Promise.all([tools.widget.set({...system, value: systemValue, color}),
    tools.widget.set({...network, value: networkValue, color: validNetwork ? "green" : "yellow"})]);
  console.log(JSON.stringify({system: systemValue, network: networkValue, color, sampleSeconds: reusable ? elapsed : null}));
} catch (error) {
  await Promise.all([tools.widget.set({...system, value: "OFFLINE", color: "red"}), tools.widget.set({...network, value: "OFFLINE", color: "red"})]);
  throw Error("Router metrics: " + String(error));
}
