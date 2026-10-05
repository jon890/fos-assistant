import { mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildBundle } from "./build.ts";

const directory = await mkdtemp(join(tmpdir(), "gmail-bundle-"));
try {
  await buildBundle(directory);
  const [built, committed] = await Promise.all([
    readFile(join(directory, "gmail-mcp.js")),
    readFile(new URL("../dist/gmail-mcp.js", import.meta.url)),
  ]);
  if (!built.equals(committed)) throw new Error("GMAIL_BUNDLE_OUTDATED");
} finally {
  await rm(directory, { recursive: true, force: true });
}
