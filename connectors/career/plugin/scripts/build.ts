import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** MCP 서버를 의존성까지 묶은 실행 파일 하나로 빌드한다. 설치본은 `bun install` 없이 이 파일만 실행한다. */
export async function buildBundle(outdir: string) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const result = await Bun.build({
    entrypoints: [resolve(root, "src/server.ts")],
    target: "bun",
    format: "esm",
    minify: true,
    outdir,
    naming: "career-mcp.js",
  });
  if (!result.success) throw new Error("CAREER_BUILD_FAILED");
  // Zod 가 만든 함수 소스에 공백만 있는 줄이 남는다. 빌드 결과를 비교하기 쉽게 그 줄을 비운다.
  for (const output of result.outputs) {
    await Bun.write(output.path, (await output.text()).replace(/^[\t ]+$/gm, ""));
  }
}

if (import.meta.main) {
  // 출력 위치를 인자로 받는다. 테스트는 임시 디렉터리를 넘겨 별도 프로세스에서 빌드한다.
  await buildBundle(resolve(process.argv[2] ?? resolve(import.meta.dir, "../dist")));
}
