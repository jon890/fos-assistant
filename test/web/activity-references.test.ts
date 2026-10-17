import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { renderToStaticMarkup } from "../../web/node_modules/react-dom/server.js";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

type SourceReadSummary = {
  completedCount: number;
  urls: string[];
  requestedUrls?: string[];
  unresolvedCount: number;
  observationComplete: boolean;
};

type MemoryUse = {
  executionId: number;
  memoryId: number;
  title: string;
  scope: "USER" | "GROUP";
  via: "ALWAYS" | "FACTS" | "SEARCH" | "READ";
};

type ActivityReferences = (input: {
  sourceReads?: SourceReadSummary | null;
  memoryUses?: MemoryUse[];
}) => React.ReactNode;

const FILE = new URL(
  "../../web/src/components/chat/activity/activity-references.tsx",
  import.meta.url,
);

async function loadActivityReferences(): Promise<ActivityReferences> {
  const source = await readFile(FILE, "utf8");
  const compiled = ts.transpileModule(source, {
    compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const module = { exports: {} as { ActivityReferences?: ActivityReferences } };
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: createRequire(FILE),
  });
  assert.ok(
    module.exports.ActivityReferences,
    "ActivityReferences를 불러오지 못했다",
  );
  return module.exports.ActivityReferences;
}

async function render(input: Parameters<ActivityReferences>[0]) {
  const ActivityReferences = await loadActivityReferences();
  return renderToStaticMarkup(ActivityReferences(input));
}

const NO_URLS = {
  completedCount: 0,
  urls: [],
  unresolvedCount: 0,
  observationComplete: true,
};

test("주소와 기억이 모두 없으면 관측 상태와 관계없이 아무것도 그리지 않는다", async () => {
  for (const sourceReads of [
    NO_URLS,
    { ...NO_URLS, observationComplete: false },
    { ...NO_URLS, requestedUrls: [] },
    { ...NO_URLS, completedCount: 3, unresolvedCount: 3 },
    null,
    undefined,
  ]) {
    assert.equal(await render({ sourceReads, memoryUses: [] }), "");
  }
});

test("결과 주소와 요청 주소를 한 목록으로 합치고 같은 주소는 한 번만 둔다", async () => {
  const html = await render({
    sourceReads: {
      ...NO_URLS,
      urls: ["https://example.com/a"],
      requestedUrls: ["https://example.com/b", "https://example.com/a"],
    },
  });
  assert.match(html, /data-testid="activity-source-reads"/);
  assert.equal(html.match(/<a /g)?.length, 2);
  assert.doesNotMatch(
    html,
    /열람 도구 완료|페이지별 성공|activity-memory-uses/,
  );
});

test("기억 제목은 평문으로만 그리고 고치는 링크를 둔다", async () => {
  const html = await render({
    memoryUses: [
      {
        executionId: 1,
        memoryId: 5,
        title: "<b>굵게</b>",
        scope: "GROUP",
        via: "FACTS",
      },
    ],
  });
  assert.match(html, /data-testid="activity-memory-uses"/);
  assert.match(html, /&lt;b&gt;굵게&lt;\/b&gt;/);
  assert.doesNotMatch(html, /<b>/);
  assert.match(html, /기억한 사실/);
  assert.match(html, /그룹/);
  assert.match(html, /href="\/memory"/);
  assert.doesNotMatch(html, /activity-source-reads/);
});
