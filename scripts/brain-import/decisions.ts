// 분석기의 보고서에서 결정 파일의 틀을 만든다(ADR-058).
//
//   node scripts/brain-import/decisions.ts init --report <report.json> --out <decisions.json>
//
// 결정 파일은 주인이 고친다. 그래서 이미 있는 파일을 덮어쓰지 않는다.
// 파일은 git 작업 디렉터리 밖에만 만든다. 표준 출력에는 decision 별 개수만 낸다.
import fs from "node:fs";
import { parseArgs } from "node:util";
import { emit, insideGitWorkTree } from "./fs-guard.ts";
import { initialDecisions, type Report } from "./lib.ts";

function fail(code: string): never {
  emit(process.stderr, { error: code });
  process.exit(1);
}

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: { report: { type: "string" }, out: { type: "string" } },
});

if (positionals[0] !== "init" || !values.report || !values.out) {
  fail("USAGE");
}
if (insideGitWorkTree(values.out)) {
  fail("OUT_INSIDE_GIT_WORK_TREE");
}
if (fs.existsSync(values.out)) {
  fail("OUT_EXISTS");
}

let report: Report;
try {
  report = JSON.parse(fs.readFileSync(values.report, "utf8")) as Report;
} catch {
  fail("REPORT_UNREADABLE");
}

let file;
try {
  file = initialDecisions(report);
} catch {
  fail("UNSUPPORTED_REPORT_VERSION");
}

fs.writeFileSync(values.out, `${JSON.stringify(file, null, 2)}\n`, { mode: 0o600, flag: "wx" });
fs.chmodSync(values.out, 0o600);

const counts = { PENDING: 0, IMPORT: 0, SKIP: 0 };
for (const item of file.items) counts[item.decision] += 1;
emit(process.stdout, counts);
