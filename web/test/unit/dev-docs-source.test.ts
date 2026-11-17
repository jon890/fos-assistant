import assert from "node:assert/strict";
import test from "node:test";
import { githubCommitUrl, githubFileUrl, sourceCommit } from "../../src/lib/dev-docs/source.ts";

test("커밋 값은 소문자 40자리 16진수일 때만 받는다", () => {
  const before = process.env.SOURCE_COMMIT;
  try {
    process.env.SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567";
    assert.equal(sourceCommit(), "0123456789abcdef0123456789abcdef01234567");
    process.env.SOURCE_COMMIT = "0123456";
    assert.equal(sourceCommit(), null);
    process.env.SOURCE_COMMIT = "0123456789ABCDEF0123456789ABCDEF01234567";
    assert.equal(sourceCommit(), null);
    process.env.SOURCE_COMMIT = " 0123456789abcdef0123456789abcdef01234567";
    assert.equal(sourceCommit(), null);
    delete process.env.SOURCE_COMMIT;
    assert.equal(sourceCommit(), null);
  } finally {
    if (before === undefined) delete process.env.SOURCE_COMMIT;
    else process.env.SOURCE_COMMIT = before;
  }
});

test("GitHub 주소는 경로를 조각마다 인코딩하고 커밋이 없으면 main 이다", () => {
  assert.equal(
    githubFileUrl("docs/adr/한글.md", null, "절"),
    `https://github.com/jon890/fos-assistant/blob/main/docs/adr/${encodeURIComponent("한글.md")}#절`,
  );
  assert.equal(githubCommitUrl("abc"), "https://github.com/jon890/fos-assistant/commit/abc");
});
