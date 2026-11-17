import assert from "node:assert/strict";
import test from "node:test";
import { normalizePosix, resolveDocLink } from "../../src/lib/dev-docs/links.ts";

const COMMIT = "a".repeat(40);
const KOREAN = "backend/docs/adr/ADR-003-기억은-주입으로-강제한다.md";
const known = new Set(["docs/prd.md", "docs/features/sample.md", KOREAN]);
const from = "docs/features/other.md";
const repo = "https://github.com/jon890/fos-assistant";

test("목록 안 상대 링크는 화면 안 문서로 가고 한글 절을 지닌다", () => {
  assert.deepEqual(resolveDocLink("sample.md#한글-절", from, known, COMMIT), {
    kind: "internal",
    href: "/dev-docs/docs/features/sample.md#한글-절",
  });
  assert.deepEqual(resolveDocLink("../prd.md", from, known, COMMIT), { kind: "internal", href: "/dev-docs/docs/prd.md" });
});

test("인코딩된 한글 ADR 링크도 목록과 맞는다", () => {
  const href = `../../backend/docs/adr/${encodeURIComponent("ADR-003-기억은-주입으로-강제한다.md")}`;
  const resolved = resolveDocLink(href, from, known, COMMIT);
  assert.equal(resolved.kind, "internal");
  assert.ok(resolved.kind === "internal" && decodeURIComponent(resolved.href) === `/dev-docs/${KOREAN}`);
});

test("목록 밖 파일과 그림은 같은 커밋의 GitHub 로, 커밋이 없으면 main 으로 간다", () => {
  assert.deepEqual(resolveDocLink("../../web/src/x.ts", from, known, COMMIT), {
    kind: "github",
    href: `${repo}/blob/${COMMIT}/web/src/x.ts`,
  });
  assert.deepEqual(resolveDocLink("../../README.md#설치", from, known, null), {
    kind: "github",
    href: `${repo}/blob/main/README.md#설치`,
  });
  assert.deepEqual(resolveDocLink("../images/x.png", from, known, COMMIT), {
    kind: "github",
    href: `${repo}/blob/${COMMIT}/docs/images/x.png`,
  });
});

test("root 밖은 저장소 첫 화면, 바깥 주소는 그대로, 다른 scheme 은 막는다", () => {
  assert.deepEqual(resolveDocLink("../../../x.md", from, known, COMMIT), { kind: "github", href: repo });
  assert.deepEqual(resolveDocLink("https://example.com/a", from, known, COMMIT), { kind: "external", href: "https://example.com/a" });
  assert.deepEqual(resolveDocLink("javascript:alert(1)", from, known, COMMIT), { kind: "blocked" });
  assert.deepEqual(resolveDocLink("#절", from, known, COMMIT), { kind: "anchor", hash: "절" });
});

test("경로 정규화는 . 과 .. 을 풀고 root 밖은 .. 을 남긴다", () => {
  assert.equal(normalizePosix("docs/features/../prd.md"), "docs/prd.md");
  assert.equal(normalizePosix("docs/../../x"), "../x");
  assert.equal(normalizePosix("./a//b"), "a/b");
});
