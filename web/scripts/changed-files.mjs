// `origin/main` 과의 공통 조상 뒤에 바뀐 web 파일 가운데 Prettier 가 다루는 것을 한 줄에 하나씩 낸다.
// web/ 에서 실행하고, 경로는 web/ 기준이다. `format:check` 와 `format:changed` 가 이 목록을 함께 쓴다.
// CI 의 unit job 은 의존을 설치하지 않으므로 Node 내장 모듈만 쓴다.
import { execFileSync } from "node:child_process";
import { extname } from "node:path";

// 마크다운은 뺀다. Prettier 는 표의 열을 공백으로 맞추는데, 이 저장소의 문서 표는 맞추지 않고 쓴다.
const PRETTIER_EXTENSIONS = new Set([
  ".js",
  ".jsx",
  ".mjs",
  ".cjs",
  ".ts",
  ".tsx",
  ".mts",
  ".cts",
  ".json",
  ".jsonc",
  ".css",
  ".scss",
  ".less",
  ".html",
  ".yml",
  ".yaml",
]);

function git(...args) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

let base;
try {
  base = git("merge-base", "HEAD", "origin/main").trim();
} catch (error) {
  console.error(
    `origin/main 과의 공통 조상을 찾지 못했다. git fetch origin 으로 받아 온 뒤 다시 실행한다.\n${error.stderr ?? error.message}`,
  );
  process.exit(2);
}

// 삭제한 파일은 검사할 것이 없으므로 뺀다(--diff-filter=d). 이름을 바꾼 파일은 새 이름으로 나온다.
const changed = git(
  "diff",
  "--name-only",
  "--relative",
  "--diff-filter=d",
  base,
  "--",
  ".",
);
const untracked = git("ls-files", "--others", "--exclude-standard", "--", ".");

const files = [...new Set(`${changed}${untracked}`.split("\n").filter(Boolean))]
  .filter((file) => PRETTIER_EXTENSIONS.has(extname(file)))
  .sort();

if (files.length > 0) {
  console.log(files.join("\n"));
}
