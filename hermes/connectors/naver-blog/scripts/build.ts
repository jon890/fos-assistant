import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export async function buildBundle(outdir: string) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const result = await Bun.build({
    entrypoints: [resolve(root, "src/server.ts")],
    target: "bun",
    format: "esm",
    minify: true,
    outdir,
    naming: "naver-blog-mcp.js",
  });
  if (!result.success) throw new Error("NAVER_BLOG_BUILD_FAILED");
  for (const output of result.outputs) {
    const bundled = await output.text();
    await Bun.write(output.path, bundled.replace(/[\t ]+$/gm, ""));
  }
}

if (import.meta.main) await buildBundle(resolve(import.meta.dir, "../dist"));
