import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  initialDecisions,
  problemOf,
  selectForBundle,
  stripFrontmatter,
  type DecisionFile,
  type DecisionItem,
  type Report,
  type ReportItem,
} from "../../scripts/brain-import/lib.ts";

const DECISIONS = new URL("../../scripts/brain-import/decisions.ts", import.meta.url).pathname;
const BUNDLE = new URL("../../scripts/brain-import/bundle.ts", import.meta.url).pathname;
const REPO_ROOT = new URL("../../", import.meta.url).pathname;
const MARKER = "평문-표식-7391";

function reportItem(overrides: Partial<ReportItem> = {}): ReportItem {
  return {
    namespace: "private",
    source_ref: "wiki/sample/note-a.md",
    source_date: "2026-01-02",
    title: "샘플 기록",
    collection: "career",
    entry_type: "DOCUMENT",
    retrieval: "SEARCH",
    sensitivity: "NORMAL",
    document_key: "sample-note",
    classification: "IMPORT_DOCUMENT",
    verdict: "NEW",
    ...overrides,
  };
}

function report(...items: ReportItem[]): Report {
  return { schema_version: 1, generated_at: "2026-10-02T00:00:00Z", items };
}

function decisionItem(overrides: Partial<DecisionItem> = {}): DecisionItem {
  return {
    id: "private/wiki/sample/note-a.md",
    classification: "IMPORT_DOCUMENT",
    verdict: "NEW",
    hold: null,
    decision: "IMPORT",
    collection: "career",
    entryType: "DOCUMENT",
    documentKey: "sample-note",
    title: "샘플 기록",
    sensitive: false,
    retrieval: "SEARCH",
    conflictResolution: null,
    holdCleared: false,
    ...overrides,
  };
}

function decisionFile(...items: DecisionItem[]): DecisionFile {
  return { schemaVersion: 1, reportGeneratedAt: "2026-10-02T00:00:00Z", items };
}

function workspace() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), "brain-import-"));
  const publicRoot = path.join(base, "public-repo");
  const privateRoot = path.join(base, "private-repo");
  fs.mkdirSync(path.join(privateRoot, "wiki/sample"), { recursive: true });
  fs.mkdirSync(publicRoot, { recursive: true });
  return { base, publicRoot, privateRoot, outDir: path.join(base, "out") };
}

function writeJson(file: string, value: unknown): string {
  fs.writeFileSync(file, JSON.stringify(value));
  return file;
}

function run(script: string, args: string[]) {
  return spawnSync(process.execPath, [script, ...args], { encoding: "utf8" });
}

function bundleArgs(ws: ReturnType<typeof workspace>, reportFile: string, decisionsFile: string) {
  return [
    "--report", reportFile,
    "--decisions", decisionsFile,
    "--public-root", ws.publicRoot,
    "--private-root", ws.privateRoot,
    "--out-dir", ws.outDir,
  ];
}

test("보고서로 만든 틀은 SKIP 은 SKIP, 나머지는 PENDING 이다", () => {
  const file = initialDecisions(
    report(
      reportItem({ source_ref: "wiki/sample/skip.md", classification: "SKIP" }),
      reportItem(),
    ),
  );
  assert.deepEqual(
    file.items.map((item) => item.decision),
    ["SKIP", "PENDING"],
  );
  assert.equal(file.reportGeneratedAt, "2026-10-02T00:00:00Z");
});

test("schema_version 이 1 이 아닌 보고서는 던진다", () => {
  assert.throws(() => initialDecisions({ ...report(reportItem()), schema_version: 2 }));
});

test("frontmatter 를 떼고 본문만 남긴다", () => {
  assert.equal(stripFrontmatter("---\ntitle: a\n---\n\n본문"), "본문");
  assert.equal(stripFrontmatter("---\ntitle: a\n본문"), "---\ntitle: a\n본문");
});

test("REVIEW_REQUIRED 항목에 종류를 적지 않으면 ENTRY_TYPE_REQUIRED 다", () => {
  assert.equal(problemOf(decisionItem({ entryType: null })), "ENTRY_TYPE_REQUIRED");
});

test("CONFLICT 항목은 KEEP_THIS 를 적어야 묶인다", () => {
  assert.equal(problemOf(decisionItem({ verdict: "CONFLICT" })), "CONFLICT_UNRESOLVED");
  assert.equal(problemOf(decisionItem({ verdict: "CONFLICT", conflictResolution: "KEEP_THIS" })), null);
});

test("보류 까닭이 있는 항목은 holdCleared 를 적어야 묶인다", () => {
  assert.equal(problemOf(decisionItem({ hold: "검토 필요" })), "HOLD_NOT_CLEARED");
  assert.equal(problemOf(decisionItem({ hold: "검토 필요", holdCleared: true })), null);
});

test("identity 항목이 민감 문서가 아니면 IDENTITY_MUST_BE_SENSITIVE_DOCUMENT 다", () => {
  assert.equal(
    problemOf(decisionItem({ collection: "identity", sensitive: false })),
    "IDENTITY_MUST_BE_SENSITIVE_DOCUMENT",
  );
  assert.equal(problemOf(decisionItem({ collection: "identity", sensitive: true })), null);
});

test("그 밖의 틀린 칸도 코드로 답한다", () => {
  assert.equal(problemOf(decisionItem({ collection: "Career" })), "COLLECTION_INVALID");
  assert.equal(problemOf(decisionItem({ documentKey: "Bad_Key" })), "DOCUMENT_KEY_INVALID");
  assert.equal(problemOf(decisionItem({ title: " " })), "TITLE_INVALID");
  assert.equal(
    problemOf(decisionItem({ entryType: "MEMORY", sensitive: true, retrieval: "ALWAYS" })),
    "SENSITIVE_ALWAYS",
  );
  assert.equal(problemOf(decisionItem({ id: `private/${"a".repeat(520)}.md` })), "SOURCE_REF_TOO_LONG");
});

test("exclude 는 identity 를 빼고 센다. only 는 identity 만 고른다", () => {
  const file = decisionFile(
    decisionItem({ id: "private/wiki/id.md", collection: "identity", sensitive: true, documentKey: "id-a" }),
    decisionItem({ id: "private/wiki/career.md" }),
  );
  const exclude = selectForBundle(file, "exclude");
  assert.deepEqual(exclude.selected.map((item) => item.collection), ["career"]);
  assert.equal(exclude.identityHeld, 1);
  const only = selectForBundle(file, "only");
  assert.deepEqual(only.selected.map((item) => item.collection), ["identity"]);
});

test("같은 collection 과 documentKey 의 DOCUMENT 둘은 둘째가 DOCUMENT_KEY_DUPLICATED 다", () => {
  const selection = selectForBundle(
    decisionFile(decisionItem({ id: "private/wiki/a.md" }), decisionItem({ id: "private/wiki/b.md" })),
    "exclude",
  );
  assert.equal(selection.selected.length, 1);
  assert.deepEqual(selection.problems, [{ id: "private/wiki/b.md", code: "DOCUMENT_KEY_DUPLICATED" }]);
});

test("PENDING 은 세고 SKIP 은 건너뛴다", () => {
  const selection = selectForBundle(
    decisionFile(decisionItem({ decision: "PENDING" }), decisionItem({ decision: "SKIP" })),
    "exclude",
  );
  assert.equal(selection.pending, 1);
  assert.equal(selection.skipped, 1);
  assert.equal(selection.selected.length, 0);
});

test("init 을 같은 --out 으로 두 번 돌리면 둘째는 멈추고 파일이 바뀌지 않는다", () => {
  const ws = workspace();
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const out = path.join(ws.base, "decisions.json");
  const first = run(DECISIONS, ["init", "--report", reportFile, "--out", out]);
  assert.equal(first.status, 0);
  assert.deepEqual(JSON.parse(first.stdout), { PENDING: 1, IMPORT: 0, SKIP: 0 });
  assert.equal(fs.statSync(out).mode & 0o777, 0o600);
  fs.writeFileSync(out, "edited");
  const second = run(DECISIONS, ["init", "--report", reportFile, "--out", out]);
  assert.equal(second.status, 1);
  assert.equal(fs.readFileSync(out, "utf8"), "edited");
});

test("init 의 --out 이 저장소 안이면 멈추고 파일이 생기지 않는다", () => {
  const ws = workspace();
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const out = path.join(REPO_ROOT, "brain-import-test-decisions.json");
  const result = run(DECISIONS, ["init", "--report", reportFile, "--out", out]);
  assert.equal(result.status, 1);
  assert.equal(fs.existsSync(out), false);
});

test("--out-dir 이 저장소 안이면 멈추고 파일이 생기지 않는다", () => {
  const ws = workspace();
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), decisionFile(decisionItem()));
  const outDir = path.join(REPO_ROOT, "brain-import-test-out");
  const args = bundleArgs({ ...ws, outDir }, reportFile, decisionsFile);
  const result = run(BUNDLE, args);
  assert.equal(result.status, 1);
  assert.equal(fs.existsSync(outDir), false);
});

test("PENDING 이 남은 결정 파일은 묶음을 만들지 않는다", () => {
  const ws = workspace();
  fs.writeFileSync(path.join(ws.privateRoot, "wiki/sample/note-a.md"), MARKER);
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(
    path.join(ws.base, "d.json"),
    decisionFile(decisionItem({ decision: "PENDING" })),
  );
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 1);
  assert.equal(fs.existsSync(path.join(ws.outDir, "bundle-001.json")), false);
});

test("다른 보고서의 결정 파일이면 멈춘다", () => {
  const ws = workspace();
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), {
    ...decisionFile(decisionItem()),
    reportGeneratedAt: "2025-01-01T00:00:00Z",
  });
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 1);
  assert.equal(fs.existsSync(path.join(ws.outDir, "bundle-001.json")), false);
});

test("IMPORT 로 적은 파일을 묶고 본문과 이름을 출력에 내지 않는다", () => {
  const ws = workspace();
  fs.writeFileSync(
    path.join(ws.privateRoot, "wiki/sample/note-a.md"),
    `---\ntitle: x\n---\n\n${MARKER}\n`,
  );
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), decisionFile(decisionItem()));
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 0, result.stderr);
  const file = path.join(ws.outDir, "bundle-001.json");
  const bundle = JSON.parse(fs.readFileSync(file, "utf8"));
  assert.equal(bundle.items[0].content, MARKER);
  assert.equal(bundle.items[0].sourceRef, "private/wiki/sample/note-a.md");
  assert.equal(bundle.items[0].sourceDate, "2026-01-02");
  assert.equal(bundle.items[0].retrieval, "SEARCH");
  for (const output of [result.stdout, result.stderr]) {
    assert.ok(!output.includes(MARKER));
    assert.ok(!output.includes("note-a"));
  }
  assert.deepEqual(JSON.parse(result.stdout), { bundles: 1, items: 1, skipped: 0, identityHeld: 0 });
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.equal(fs.statSync(ws.outDir).mode & 0o777, 0o700);
});

test("본문이 12,001자인 파일은 CONTENT_TOO_LONG 으로 멈추고 묶음이 없다", () => {
  const ws = workspace();
  fs.writeFileSync(path.join(ws.privateRoot, "wiki/sample/note-a.md"), "가".repeat(12001));
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), decisionFile(decisionItem()));
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 1);
  const problems = JSON.parse(fs.readFileSync(path.join(ws.outDir, "problems.json"), "utf8"));
  assert.equal(problems[0].code, "CONTENT_TOO_LONG");
  assert.equal(fs.existsSync(path.join(ws.outDir, "bundle-001.json")), false);
  assert.ok(!result.stderr.includes("note-a"));
});

test("root 밖을 가리키는 심볼릭 링크는 PATH_OUTSIDE_ROOT 다", () => {
  const ws = workspace();
  const outside = path.join(ws.base, "outside.md");
  fs.writeFileSync(outside, MARKER);
  fs.symlinkSync(outside, path.join(ws.privateRoot, "wiki/sample/note-a.md"));
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), decisionFile(decisionItem()));
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 1);
  const problems = JSON.parse(fs.readFileSync(path.join(ws.outDir, "problems.json"), "utf8"));
  assert.equal(problems[0].code, "PATH_OUTSIDE_ROOT");
});

test("확장자가 md 와 txt 가 아니면 UNSUPPORTED_FILE, 본문이 비면 CONTENT_EMPTY 다", () => {
  const ws = workspace();
  fs.writeFileSync(path.join(ws.privateRoot, "wiki/sample/a.pdf"), "x");
  fs.writeFileSync(path.join(ws.privateRoot, "wiki/sample/b.md"), "---\ntitle: x\n---\n");
  const reportFile = writeJson(path.join(ws.base, "report.json"), report(reportItem()));
  const decisionsFile = writeJson(
    path.join(ws.base, "d.json"),
    decisionFile(
      decisionItem({ id: "private/wiki/sample/a.pdf", documentKey: "doc-a" }),
      decisionItem({ id: "private/wiki/sample/b.md", documentKey: "doc-b" }),
    ),
  );
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 1);
  const codes = JSON.parse(fs.readFileSync(path.join(ws.outDir, "problems.json"), "utf8")).map(
    (problem: { code: string }) => problem.code,
  );
  assert.deepEqual(codes, ["UNSUPPORTED_FILE", "CONTENT_EMPTY"]);
});

test("항목 101개는 100개와 1개로 나눠 묶는다", () => {
  const ws = workspace();
  const items: DecisionItem[] = [];
  for (let i = 0; i < 101; i += 1) {
    fs.writeFileSync(path.join(ws.privateRoot, `wiki/sample/n${i}.md`), `본문 ${i}`);
    items.push(decisionItem({ id: `private/wiki/sample/n${i}.md`, documentKey: `doc-${i}` }));
  }
  const reportFile = writeJson(path.join(ws.base, "report.json"), report());
  const decisionsFile = writeJson(path.join(ws.base, "d.json"), decisionFile(...items));
  const result = run(BUNDLE, bundleArgs(ws, reportFile, decisionsFile));
  assert.equal(result.status, 0, result.stderr);
  const first = JSON.parse(fs.readFileSync(path.join(ws.outDir, "bundle-001.json"), "utf8"));
  const second = JSON.parse(fs.readFileSync(path.join(ws.outDir, "bundle-002.json"), "utf8"));
  assert.equal(first.items.length, 100);
  assert.equal(second.items.length, 1);
});

test("신원 묶음은 identity-bundle 로 따로 만든다", () => {
  const ws = workspace();
  fs.writeFileSync(path.join(ws.privateRoot, "wiki/sample/note-a.md"), MARKER);
  const reportFile = writeJson(path.join(ws.base, "report.json"), report());
  const decisionsFile = writeJson(
    path.join(ws.base, "d.json"),
    decisionFile(decisionItem({ collection: "identity", sensitive: true })),
  );
  const result = run(BUNDLE, [...bundleArgs(ws, reportFile, decisionsFile), "--identity", "only"]);
  assert.equal(result.status, 0, result.stderr);
  assert.ok(fs.existsSync(path.join(ws.outDir, "identity-bundle-001.json")));
  assert.equal(fs.existsSync(path.join(ws.outDir, "bundle-001.json")), false);
});

test("clean 은 묶음과 problems.json 만 지우고 다른 파일은 남긴다", () => {
  const ws = workspace();
  fs.mkdirSync(ws.outDir);
  for (const name of ["bundle-001.json", "identity-bundle-001.json", "problems.json", "keep.txt"]) {
    fs.writeFileSync(path.join(ws.outDir, name), "x");
  }
  const result = run(BUNDLE, ["clean", "--out-dir", ws.outDir]);
  assert.equal(result.status, 0);
  assert.deepEqual(JSON.parse(result.stdout), { removed: 3 });
  assert.deepEqual(fs.readdirSync(ws.outDir), ["keep.txt"]);
});
