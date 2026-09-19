import { expect, test } from "bun:test";
import { operationInput } from "../src/commands/input";
import { scheduleArgs } from "../src/commands/schedule";
import { Catalog } from "../src/api";
import { document } from "./fixture";

test("path positionals remain typed and reject duplicate location-qualified flags", async () => {
  const op = new Catalog(document()).byId.get("admin.users.get")!;
  expect((await operationInput(op, ["a/b +?", "--query.id", "9"]))).toEqual({ path: { id: "a/b +?" }, query: { id: 9 } });
  await expect(operationInput(op, ["first", "--path.id", "second"])).rejects.toThrow("Duplicate");
  await expect(operationInput(op, ["first", "second"])).rejects.toThrow("Unexpected argument");
  await expect(operationInput(op, ["first", "--input", "{}"])).rejects.toThrow("alone");
});

test("schedule shorthand preserves argv literally and rejects ambiguous configuration", () => {
  const args = scheduleArgs("schedule.new", ["test", "--every", "1h", "--resource", "cpu=10,memory=20", "--", "/bin/echo", "--help", "--url", "$HOME", "&&"]);
  expect(args.slice(0, 2)).toEqual(["test", "--body"]);
  expect(JSON.parse(args[2]!)).toEqual({ every: "1h", resource: { cpu: 10, memory: 20 }, enabled: true, command: ["/bin/echo", "--help", "--url", "$HOME", "&&"] });
  for (const resource of ["cpu=", "cpu=-1", "cpu=Infinity", "cpu=1=2", "cpu=1,cpu=2"]) {
    expect(() => scheduleArgs("schedule.new", ["test", "--every", "1h", "--resource", resource, "--", "true"])).toThrow("resource");
  }
  expect(() => scheduleArgs("schedule.new", ["test", "--every", "1h", "--body", "{}", "--", "true"])).toThrow("either");
  expect(() => scheduleArgs("schedule.new", ["test", "--every", "1h", "--"])).toThrow("missing command");
  expect(scheduleArgs("schedule.config.write", [])).toEqual(["--body", "-"]);
});
