#!/usr/bin/env node
// CI의 bun-version을 읽어 로컬 버전과 다르면 알린다. 경고만 하고 검사를 멈추지 않는다.
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const workflow = process.argv[2] ?? fileURLToPath(new URL("../../.github/workflows/ci.yml", import.meta.url));
let source: string;
try {
  source = readFileSync(workflow, "utf8");
} catch {
  console.log(`알림: ${workflow} 를 읽지 못해 bun 버전 비교를 건너뛴다.`);
  process.exit(0);
}
const wanted = [...new Set([...source.matchAll(/^\s*bun-version:\s*['"]?([^'"\s#]+)/gm)].map(match => match[1]))];
if (!wanted.length) {
  console.log(`알림: ${basename(workflow)} 에서 bun-version 을 찾지 못해 bun 버전 비교를 건너뛴다.`);
} else {
  const result = spawnSync("bun", ["--version"], { encoding: "utf8" });
  if (result.error || result.status !== 0) {
    console.log("알림: 로컬 bun 버전을 구하지 못해 비교를 건너뛴다.");
  } else {
    const local = result.stdout.trim();
    if (wanted.length !== 1 || wanted[0] !== local) {
      console.log(`알림: 로컬 bun ${local} 이 CI 의 bun ${wanted.join(" 과 ")} 과 다르다. ` +
        `손대지 않은 금융 왕복 검사가 실패할 수 있다. 맞추려면 \`bun upgrade --version ${wanted[0]}\` ` +
        `(mise 면 \`mise use -g bun@${wanted[0]}\`) 를 쓴다.`);
    }
  }
}
