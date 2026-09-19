import { expect, test } from "bun:test";
import { operationInput } from "../src/commands/input";
import { Catalog } from "../src/api";
import { document } from "./fixture";

test("path positionals remain typed and reject duplicate location-qualified flags", async () => {
  const op = new Catalog(document()).byId.get("admin.users.get")!;
  expect((await operationInput(op, ["a/b +?", "--query.id", "9"]))).toEqual({ path: { id: "a/b +?" }, query: { id: 9 } });
  await expect(operationInput(op, ["first", "--path.id", "second"])).rejects.toThrow("Duplicate");
  await expect(operationInput(op, ["first", "second"])).rejects.toThrow("Unexpected argument");
  await expect(operationInput(op, ["first", "--input", "{}"])).rejects.toThrow("alone");
});
