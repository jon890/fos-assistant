import assert from "node:assert/strict";
import test from "node:test";
import { buildCatalog, type DocGroup } from "../../src/lib/dev-docs/catalog.ts";
import { filterCatalog } from "../../src/lib/dev-docs/filter.ts";
import { KOREAN_ADR, makeDocsTree } from "./dev-docs-tree.ts";

function group(groups: DocGroup[], key: string) {
  const found = groups.find((g) => g.key === key);
  assert.ok(found, key);
  return found;
}

test("목록은 제품, 기능, 결정, 코드 규칙 순서이고 있는 제품 문서만 든다", async () => {
  const { groups } = await buildCatalog(await makeDocsTree());
  assert.deepEqual(groups.map((g) => g.label), ["제품", "기능", "결정", "코드 규칙"]);
  assert.deepEqual(
    group(groups, "product").sections[0].entries.map((e) => [e.path, e.title]),
    [
      ["docs/prd.md", "무엇을 만드는가"],
      ["docs/code-architecture.md", "코드 구조"],
      ["hermes/docs/hermes-contract.md", "Hermes 계약"],
    ],
  );
});

test("기능은 펜스 밖 첫 제목을 쓰고 폴더의 스펙과 설계만 펼치며 계획 파일은 넣지 않는다", async () => {
  const catalog = await buildCatalog(await makeDocsTree());
  const entries = group(catalog.groups, "features").sections[0].entries;
  assert.deepEqual(entries.map((e) => e.title), ["다른 기능", "견본 기능"]);
  assert.deepEqual(entries[1].children?.map((c) => c.path), [
    "docs/features/sample/spec.md",
    "docs/features/sample/design.md",
  ]);
  assert.equal(entries[0].children, undefined);
  assert.equal(catalog.paths.has("docs/features/sample/tasks/plan-a/phase-01.md"), false);
});

test("결정은 나눔마다 INDEX 를 첫 줄로 두고 표 순서, 상태 첫 문장, 보관, 한글 파일 이름을 따른다", async () => {
  const catalog = await buildCatalog(await makeDocsTree());
  const [common, backend] = group(catalog.groups, "decisions").sections;
  assert.equal(common.label, "공통");
  assert.deepEqual(
    common.entries.map((e) => [e.path, e.title, e.status]),
    [
      ["docs/adr/INDEX.md", "ADR 목록", undefined],
      ["docs/adr/ADR-001-runtime.md", "런타임을 둔다", "Accepted"],
    ],
  );
  assert.deepEqual(common.archived?.map((e) => [e.path, e.status]), [
    ["docs/adr/archive/ADR-005-old.md", "Superseded (ADR-001)"],
  ]);
  assert.equal(backend.label, "backend");
  assert.deepEqual(backend.entries.map((e) => e.path), ["backend/docs/adr/INDEX.md", KOREAN_ADR]);
  assert.equal(catalog.paths.has("docs/adr/ADR-009-missing.md"), false);
});

test("코드 규칙은 INDEX 다음 번호 순이다", async () => {
  const { groups } = await buildCatalog(await makeDocsTree());
  assert.deepEqual(
    group(groups, "rules").sections[0].entries.map((e) => e.fileStem),
    ["INDEX", "rule-2", "rule-10"],
  );
});

test("찾기는 제목과 파일 이름을 맞추고, 자식만 맞으면 펼치며, spec 같은 파일 이름으로는 펼치지 않는다", async () => {
  const { groups } = await buildCatalog(await makeDocsTree());
  const byTitle = filterCatalog(groups, "다른");
  assert.deepEqual(byTitle.map((g) => g.key), ["features"]);
  assert.deepEqual(byTitle[0].sections[0].entries.map((e) => e.title), ["다른 기능"]);

  const byStem = filterCatalog(groups, "RULE-10");
  assert.deepEqual(byStem[0].sections[0].entries.map((e) => e.fileStem), ["rule-10"]);

  const byChild = filterCatalog(groups, "스펙 제목");
  const entry = byChild[0].sections[0].entries[0];
  assert.equal(entry.title, "견본 기능");
  assert.equal(entry.expanded, true);

  assert.deepEqual(filterCatalog(groups, "spec"), []);
  assert.deepEqual(filterCatalog(groups, "없는 글자"), []);
  assert.equal(filterCatalog(groups, "  "), groups);
});
