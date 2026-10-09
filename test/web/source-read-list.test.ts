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

type SourceReadList = (input: {
  sourceReads: SourceReadSummary;
}) => React.ReactNode;

async function loadSourceReadList(): Promise<SourceReadList> {
  const source = await readFile(
    new URL(
      "../../web/src/components/chat/source-read-list.tsx",
      import.meta.url,
    ),
    "utf8",
  );
  const compiled = ts.transpileModule(source, {
    compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText;
  const module = { exports: {} as { SourceReadList?: SourceReadList } };
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: createRequire(
      new URL(
        "../../web/src/components/chat/source-read-list.tsx",
        import.meta.url,
      ),
    ),
  });
  assert.ok(module.exports.SourceReadList, "SourceReadList를 불러오지 못했다");
  return module.exports.SourceReadList;
}

async function renderSourceReads(sourceReads: SourceReadSummary) {
  const SourceReadList = await loadSourceReadList();
  return renderToStaticMarkup(SourceReadList({ sourceReads }));
}

test("완료와 두 주소 목록이 모두 없으면 관측 상태와 관계없이 원문 구역을 숨긴다", async () => {
  for (const sourceReads of [
    {
      completedCount: 0,
      urls: [],
      unresolvedCount: 0,
      observationComplete: true,
    },
    {
      completedCount: 0,
      urls: [],
      unresolvedCount: 0,
      observationComplete: false,
    },
    {
      completedCount: 0,
      urls: [],
      requestedUrls: undefined,
      unresolvedCount: 0,
      observationComplete: false,
    },
    {
      completedCount: 0,
      urls: [],
      requestedUrls: [],
      unresolvedCount: 0,
      observationComplete: true,
    },
  ]) {
    assert.equal(await renderSourceReads(sourceReads), "");
  }
});

test("열람 완료 또는 결과·요청 주소가 있으면 기존 원문 구역을 보인다", async () => {
  for (const sourceReads of [
    {
      completedCount: 1,
      urls: [],
      unresolvedCount: 0,
      observationComplete: true,
    },
    {
      completedCount: 0,
      urls: ["https://example.com/result"],
      unresolvedCount: 0,
      observationComplete: true,
    },
    {
      completedCount: 0,
      urls: [],
      requestedUrls: ["https://example.com/requested"],
      unresolvedCount: 0,
      observationComplete: true,
    },
  ]) {
    const html = await renderSourceReads(sourceReads);
    assert.match(html, /data-testid="source-reads"/);
  }
});
