import { mkdtemp, rm, readFile, readdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildBundle } from "./build.ts";

const filename = "tossinvest-mcp.js";
const mcp = await Bun.file(new URL("../.mcp.json", import.meta.url)).json();
const server = mcp.mcpServers["tossinvest"];
if (
  JSON.stringify(Object.keys(mcp.mcpServers)) !==
    JSON.stringify(["tossinvest"]) ||
  server.command !== "bun" ||
  JSON.stringify(server.args) !==
    JSON.stringify([`\${CLAUDE_PLUGIN_ROOT}/dist/${filename}`])
)
  throw new Error("INVALID_CONNECTOR_ENTRY");

const directory = await mkdtemp(join(tmpdir(), "tossinvest-bundle-"));
try {
  const first = join(directory, "first");
  const second = join(directory, "second");
  await buildBundle(first);
  await buildBundle(second);
  for (const output of [first, second]) {
    if (JSON.stringify(await readdir(output)) !== JSON.stringify([filename]))
      throw new Error("BUNDLE_INVENTORY_MISMATCH");
  }
  const built = await readFile(join(first, filename));
  if (!built.equals(await readFile(join(second, filename))))
    throw new Error("CONNECTOR_BUILD_NOT_REPRODUCIBLE");
  if (
    !built.equals(
      await readFile(new URL(`../dist/${filename}`, import.meta.url)),
    )
  )
    throw new Error("LOCAL_BUNDLE_MISMATCH");
} finally {
  await rm(directory, { recursive: true, force: true });
}
