import assert from "node:assert/strict";
import test from "node:test";
import { extractRoadmap, normalizeRepoPath, readDoc, readRoadmap } from "../../src/lib/dev-docs/read.ts";
import { KOREAN_ADR, makeDocsTree } from "./dev-docs-tree.ts";

async function withRoot<T>(run: () => Promise<T>): Promise<T> {
  const before = process.env.DEV_DOCS_ROOT;
  try {
    process.env.DEV_DOCS_ROOT = await makeDocsTree();
    return await run();
  } finally {
    if (before === undefined) delete process.env.DEV_DOCS_ROOT;
    else process.env.DEV_DOCS_ROOT = before;
  }
}

test("목록에 든 문서는 읽고 목록 밖과 root 밖 경로는 열지 않는다", () =>
  withRoot(async () => {
    assert.equal((await readDoc(["docs", "features", "sample.md"]))?.markdown, "# 견본 기능\n");
    assert.equal(await readDoc("docs/features/sample/tasks/plan-a/phase-01.md"), null);
    assert.equal(await readDoc("docs/nope.md"), null);
    assert.equal(await readDoc("../etc/passwd"), null);
    assert.equal(await readDoc("/etc/passwd"), null);
    assert.equal(await readDoc("%2e%2e/docs/prd.md"), null);
    assert.equal(await readDoc("docs/features/../features/sample.md").then((d) => d?.path), "docs/features/sample.md");
  }));

test("인코딩된 한글 ADR 경로도 목록과 맞아 열린다", () =>
  withRoot(async () => {
    const encoded = KOREAN_ADR.split("/").map(encodeURIComponent);
    assert.equal((await readDoc(encoded))?.path, KOREAN_ADR);
  }));

test("문서 폴더가 없으면 아무것도 열지 않는다", async () => {
  const before = process.env.DEV_DOCS_ROOT;
  delete process.env.DEV_DOCS_ROOT;
  try {
    assert.equal(await readDoc("docs/prd.md"), null);
    assert.equal(await readRoadmap(), null);
  } finally {
    if (before !== undefined) process.env.DEV_DOCS_ROOT = before;
  }
});

test("경로 풀기는 인코딩 오류와 root 밖을 null 로 돌린다", () => {
  assert.equal(normalizeRepoPath("%E0%A4%A"), null);
  assert.equal(normalizeRepoPath("a/../../b"), null);
  assert.equal(normalizeRepoPath(""), null);
  assert.equal(normalizeRepoPath("docs/./prd.md"), "docs/prd.md");
});

test("로드맵은 그 절만 자르고 펜스 안 제목은 경계로 보지 않는다", () =>
  withRoot(async () => {
    const roadmap = await readRoadmap();
    assert.ok(roadmap?.startsWith("## 로드맵"));
    assert.ok(roadmap?.includes("## 펜스 안 제목"));
    assert.ok(!roadmap?.includes("범위 밖"));
    assert.equal(extractRoadmap("# 제목\n\n## 범위\n"), null);
    assert.equal(extractRoadmap("## 로드맵\n\n끝 줄"), "## 로드맵\n\n끝 줄");
  }));
