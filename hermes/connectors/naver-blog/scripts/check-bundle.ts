import { mkdtemp, rm, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { buildBundle } from "./build.ts";

const directory = await mkdtemp(join(tmpdir(), "naver-blog-bundle-"));
try {
  await buildBundle(directory);
  const [built, committed] = await Promise.all([
    readFile(join(directory, "naver-blog-mcp.js")),
    readFile(new URL("../dist/naver-blog-mcp.js", import.meta.url)),
  ]);
  if (!built.equals(committed)) throw new Error("NAVER_BLOG_BUNDLE_OUTDATED");
} finally {
  await rm(directory, { recursive: true, force: true });
}
