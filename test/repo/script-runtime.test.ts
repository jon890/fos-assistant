// 개발 도구는 TypeScript로 쓰고 실제 Hermes plugin과 그 검증만 Python을 허용한다.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";

test("Python 파일은 Hermes runtime·plugin과 그 테스트에만 둔다", () => {
  const root = fileURLToPath(new URL("../../", import.meta.url));
  const files = execFileSync("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "*.py"], { cwd: root, encoding: "utf8" }).split("\0").filter(Boolean);
  assert.deepEqual(files.filter(file => existsSync(new URL(`../../${file}`, import.meta.url)) && !/^hermes\/(plugins|runtime|tests)\//.test(file)), []);
});
