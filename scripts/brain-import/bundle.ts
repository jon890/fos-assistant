// 주인이 검토한 결정 파일로 묶음 파일을 만든다(ADR-058).
//
//   node scripts/brain-import/bundle.ts --report <report.json> --decisions <decisions.json> \
//     --public-root <공개 저장소 root> --private-root <비공개 저장소 root> \
//     --out-dir <저장소 밖의 디렉터리> [--identity exclude|only]
//   node scripts/brain-import/bundle.ts clean --out-dir <같은 디렉터리>
//
// 묶음은 민감 본문을 평문으로 담는다. 저장소 밖에만 만들고, 가져오기가 끝나면 `clean` 으로 지운다.
// 커밋하지 않는다. 표준 출력과 표준 오류에는 개수와 결과 코드만 내고 제목, 경로, 본문을 내지 않는다.
import fs from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";
import { emit, insideGitWorkTree } from "./fs-guard.ts";
import {
  chunkBySize,
  MAX_CONTENT_CHARS,
  MAX_BUNDLE_BYTES,
  MAX_ITEMS_PER_BUNDLE,
  retrievalFor,
  selectForBundle,
  stripFrontmatter,
  type Bundle,
  type BundleItem,
  type DecisionFile,
  type DecisionItem,
  type Report,
} from "./lib.ts";

const BUNDLE_FILE = /^(identity-)?bundle-\d+\.json$/;

function fail(code: string, detail: Record<string, unknown> = {}): never {
  emit(process.stderr, { error: code, ...detail });
  process.exit(1);
}

function countBy(problems: { code: string }[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const problem of problems) counts[problem.code] = (counts[problem.code] ?? 0) + 1;
  return counts;
}

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    report: { type: "string" },
    decisions: { type: "string" },
    "public-root": { type: "string" },
    "private-root": { type: "string" },
    "out-dir": { type: "string" },
    identity: { type: "string", default: "exclude" },
  },
});

const outDir = values["out-dir"];
if (!outDir) fail("USAGE");
if (insideGitWorkTree(outDir)) fail("OUT_INSIDE_GIT_WORK_TREE");

if (positionals[0] === "clean") {
  let removed = 0;
  if (fs.existsSync(outDir)) {
    for (const name of fs.readdirSync(outDir)) {
      if (BUNDLE_FILE.test(name) || name === "problems.json") {
        fs.rmSync(path.join(outDir, name));
        removed += 1;
      }
    }
  }
  emit(process.stdout, { removed });
  process.exit(0);
}

const identityMode = values.identity;
if (identityMode !== "exclude" && identityMode !== "only") fail("USAGE");
if (!values.report || !values.decisions || !values["public-root"] || !values["private-root"]) {
  fail("USAGE");
}

let report: Report;
let decisions: DecisionFile;
try {
  report = JSON.parse(fs.readFileSync(values.report, "utf8")) as Report;
  decisions = JSON.parse(fs.readFileSync(values.decisions, "utf8")) as DecisionFile;
} catch {
  fail("INPUT_UNREADABLE");
}
if (report.schema_version !== 1 || decisions.schemaVersion !== 1) fail("UNSUPPORTED_VERSION");
// 결정 파일이 다른 보고서에서 만든 것이면 항목이 서로 맞지 않는다.
if (decisions.reportGeneratedAt !== report.generated_at) fail("REPORT_MISMATCH");

const selection = selectForBundle(decisions, identityMode);
const problems = [...selection.problems];

const roots: Record<string, string> = {
  public: fs.realpathSync(values["public-root"]),
  private: fs.realpathSync(values["private-root"]),
};
const reportDates = new Map<string, string | null>();
for (const item of report.items) {
  reportDates.set(`${item.namespace}/${item.source_ref}`, item.source_date ?? null);
}

function readContent(item: DecisionItem): { content: string } | { code: string } {
  const namespace = item.id.slice(0, item.id.indexOf("/"));
  const relative = item.id.slice(item.id.indexOf("/") + 1);
  const root = roots[namespace];
  if (root === undefined) return { code: "NAMESPACE_UNKNOWN" };
  const extension = path.extname(relative);
  if (extension !== ".md" && extension !== ".txt") return { code: "UNSUPPORTED_FILE" };
  const candidate = path.resolve(root, relative);
  if (!fs.existsSync(candidate)) return { code: "FILE_NOT_FOUND" };
  // `..` 과 심볼릭 링크는 실제 경로가 root 밖으로 나가는지로 막는다.
  const real = fs.realpathSync(candidate);
  if (real !== root && !real.startsWith(root + path.sep)) return { code: "PATH_OUTSIDE_ROOT" };
  const content = stripFrontmatter(fs.readFileSync(real, "utf8"));
  if (content === "") return { code: "CONTENT_EMPTY" };
  if (content.length > MAX_CONTENT_CHARS) return { code: "CONTENT_TOO_LONG" };
  return { content };
}

// 문제가 하나라도 있으면 묶음을 만들지 않지만, 한 번에 모두 알리려고 파일 문제도 끝까지 모은다.
const bundleItems: BundleItem[] = [];
for (const item of selection.selected) {
  const read = readContent(item);
  if ("code" in read) {
    problems.push({ id: item.id, code: read.code });
    continue;
  }
  bundleItems.push({
    sourceRef: item.id,
    sourceDate: reportDates.get(item.id) ?? null,
    collection: item.collection,
    entryType: item.entryType!,
    documentKey: item.entryType === "DOCUMENT" ? item.documentKey : null,
    title: item.title,
    content: read.content,
    sensitive: item.sensitive,
    retrieval: retrievalFor(item),
  });
}

fs.mkdirSync(outDir, { recursive: true, mode: 0o700 });
fs.chmodSync(outDir, 0o700);

if (selection.pending > 0 || problems.length > 0) {
  // 문제 파일의 이름은 개인 저장소의 경로라 출력 자리의 파일에만 적는다.
  fs.writeFileSync(path.join(outDir, "problems.json"), `${JSON.stringify(problems, null, 2)}\n`, {
    mode: 0o600,
  });
  fs.chmodSync(path.join(outDir, "problems.json"), 0o600);
  fail("NOT_BUNDLED", { pending: selection.pending, problems: countBy(problems) });
}

const prefix = identityMode === "only" ? "identity-bundle" : "bundle";
const stale = fs.readdirSync(outDir).filter((name) => name.startsWith(`${prefix}-`) && BUNDLE_FILE.test(name));
if (stale.length > 0) fail("OUT_DIR_NOT_CLEAN", { existing: stale.length });
// 앞 실행이 남긴 문제 목록은 이번 결과와 맞지 않는다.
fs.rmSync(path.join(outDir, "problems.json"), { force: true });

const createdAt = new Date().toISOString();
const parts = chunkBySize(bundleItems, MAX_ITEMS_PER_BUNDLE, MAX_BUNDLE_BYTES);
parts.forEach((items, index) => {
  const bundle: Bundle = { schemaVersion: 1, createdAt, items };
  const name = `${prefix}-${String(index + 1).padStart(3, "0")}.json`;
  const file = path.join(outDir, name);
  fs.writeFileSync(file, JSON.stringify(bundle), { mode: 0o600 });
  fs.chmodSync(file, 0o600);
});

emit(process.stdout, {
  bundles: parts.length,
  items: bundleItems.length,
  skipped: selection.skipped,
  identityHeld: selection.identityHeld,
});
