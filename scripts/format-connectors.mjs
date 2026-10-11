// web 에 설치한 Prettier 로 변경된 커넥터 TypeScript 를 검사하거나 고친다.
// 생성 계약 구간은 generator --check 가 검사하고 여기서는 수작업 구간만 포맷한다.
import { execFileSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const require = createRequire(new URL("../web/package.json", import.meta.url));
const prettier = require("prettier");
const mode = process.argv[2];
if (mode !== "check" && mode !== "fix") {
  console.error("사용법: node scripts/format-connectors.mjs <check|fix>");
  process.exit(2);
}

function git(...args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
}

const base = git("merge-base", "HEAD", "origin/main");
const changed = git(
  "diff",
  "--name-only",
  "-z",
  "--diff-filter=d",
  base,
  "--",
  "hermes/connectors",
);
const untracked = git(
  "ls-files",
  "--others",
  "--exclude-standard",
  "-z",
  "--",
  "hermes/connectors",
);
const files = [...new Set([...changed.split("\0"), ...untracked.split("\0")])]
  .filter((file) => /\.(?:ts|tsx|mts|cts)$/.test(file))
  .sort();

let failed = false;
for (const file of files) {
  const absolute = resolve(root, file);
  const before = await readFile(absolute, "utf8");
  // 생성 구간은 보존하고 나머지 문법과 공백은 일반 Prettier 규칙으로 검사한다.
  const marker = "// CONTRACT-GENERATED:PLACEHOLDER";
  let generated;
  if (file === "hermes/connectors/tossinvest/src/api-contract-generated.ts") {
    const starts = before.split("// CONTRACT-GENERATED:START").length - 1;
    const ends = before.split("// CONTRACT-GENERATED:END").length - 1;
    generated = before.match(
      /\/\/ CONTRACT-GENERATED:START[\s\S]*?\/\/ CONTRACT-GENERATED:END/,
    )?.[0];
    if (starts !== 1 || ends !== 1 || !generated || before.includes(marker)) {
      throw new Error(`생성 계약 구간 표시가 올바르지 않다: ${file}`);
    }
  }
  const source = generated ? before.replace(generated, marker) : before;
  const options = await prettier.resolveConfig(absolute);
  const formatted = await prettier.format(source, {
    ...options,
    filepath: absolute,
  });
  const after = generated ? formatted.replace(marker, generated) : formatted;
  if (before === after) continue;
  if (mode === "fix") {
    await writeFile(absolute, after);
    console.log(`포맷 수정: ${file}`);
  } else {
    console.error(`포맷 위반: ${file}`);
    failed = true;
  }
}
if (failed) process.exit(1);
console.log(`커넥터 TypeScript 포맷: ${files.length}개 파일 통과`);
