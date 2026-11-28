import { afterAll, beforeAll, expect, test } from "bun:test";
import { mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const dist = join(import.meta.dir, "../dist");

/**
 * 빌드를 별도 bun 프로세스에서 실행한다. 같은 `bun test` 프로세스에서 Bun.build 를 부르면
 * 함께 실행하는 테스트 파일의 해석 상태를 공유해, `.js` 로 적은 TypeScript import 를
 * 못 찾는 경우가 있다. `bun run build` 와 같은 조건에서 빌드한다.
 */
async function buildInto(outdir: string) {
  const proc = Bun.spawn(["bun", join(import.meta.dir, "build.ts"), outdir], {
    stdout: "pipe",
    stderr: "pipe",
  });
  const [stderr, exitCode] = await Promise.all([new Response(proc.stderr).text(), proc.exited]);
  if (exitCode !== 0) throw new Error(`build exited with ${exitCode}: ${stderr}`);
}

let first = "";
let second = "";

beforeAll(async () => {
  first = mkdtempSync(join(tmpdir(), "career-bundle-"));
  second = mkdtempSync(join(tmpdir(), "career-bundle-"));
  await Promise.all([buildInto(first), buildInto(second)]);
}, 60_000);

afterAll(() => {
  for (const out of [first, second]) if (out) rmSync(out, { recursive: true, force: true });
});

test("독립 빌드 두 번이 실행 파일 하나를 같은 바이트로 만든다", () => {
  expect(readdirSync(first)).toEqual(["career-mcp.js"]);
  expect(readdirSync(second)).toEqual(["career-mcp.js"]);
  expect(readFileSync(join(first, "career-mcp.js"))).toEqual(readFileSync(join(second, "career-mcp.js")));
});

test("로컬 dist 의 실행 파일이 지금 소스의 빌드와 같다", () => {
  expect(readFileSync(join(dist, "career-mcp.js"), "utf8")).toBe(readFileSync(join(first, "career-mcp.js"), "utf8"));
});
