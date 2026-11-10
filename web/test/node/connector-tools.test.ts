import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { renderToStaticMarkup } from "../../web/node_modules/react-dom/server.js";
import * as connection from "../../web/src/lib/connection.ts";
import { toolText } from "../../web/src/lib/connector-tool-text.ts";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

type ToolsModule = {
  ConnectorTools: (props: {
    tools: connection.ConnectorTool[];
  }) => React.ReactNode;
  StandardPluginTools: (props: {
    listedTools: number | null;
    readOnlyTools: number | null;
  }) => React.ReactNode;
};

async function render(tools: connection.ConnectorTool[]) {
  const loaded = await load();
  return renderToStaticMarkup(loaded.ConnectorTools({ tools }));
}

async function load(): Promise<ToolsModule> {
  const source = await readFile(
    new URL(
      "../../web/src/components/connector/connector-tools.tsx",
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
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const module = { exports: {} as ToolsModule };
  const dependencies: Record<string, unknown> = {
    "@/lib/connection": connection,
    "@/lib/connector-tool-text": { toolText },
    "@/components/ui/badge": {
      Badge: ({ children }: { children: React.ReactNode }) => children,
    },
    "@/components/ui/notice": {
      Notice: ({ children }: { children: React.ReactNode }) => children,
    },
  };
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: (name: string) => dependencies[name] ?? webRequire(name),
  });
  return module.exports;
}

test("실제 도구 컴포넌트는 요약을 HTML로 실행하지 않고 제목과 정책 밑에 평문으로 그린다", async () => {
  const html = await render([
    {
      name: "write_secret_tool",
      title: "쓰기",
      risk: "WRITE",
      approval: "REQUIRED",
      grant: false,
      summary: "<script>alert(1)</script>\n**요약**\t입력",
    },
  ]);
  assert.ok(
    html.includes("&lt;script&gt;alert(1)&lt;/script&gt;\n**요약**\t입력"),
  );
  assert.ok(!html.includes("<script>"));
  assert.ok(!html.includes("write_secret_tool"));
  assert.ok(
    html.indexOf("실행할 때마다 물어봐요") <
      html.indexOf("connector-tool-caption"),
  );
});

test("제목이 없거나 빈 도구는 실제 이름 대신 대체 이름을 보이고 빈·불량 요약은 그리지 않는다", async () => {
  for (const summary of [undefined, null, "", "글\u202e", "\ud800"]) {
    for (const title of [null, " "]) {
      const html = await render([
        {
          name: "read_hidden_tool",
          title,
          risk: "READ",
          approval: "NONE",
          grant: false,
          summary,
        },
      ]);
      assert.ok(!html.includes("connector-tool-caption"));
      assert.ok(!html.includes("read_hidden_tool"));
      assert.ok(html.includes("이름 없는 도구"));
      assert.ok(html.includes("바로 실행해요"));
    }
  }
  assert.ok((await render([])).includes("connector-tools-empty"));
});

test("표준 플러그인은 모든 도구를 쓸 때마다 확인한다고 알리고 마지막 확인에서 받은 도구 수와 읽기 도구 수를 보인다", async () => {
  const loaded = await load();
  const counted = renderToStaticMarkup(
    loaded.StandardPluginTools({ listedTools: 3, readOnlyTools: 1 }),
  );
  assert.match(counted, /모든 도구를 쓸 때마다 확인해요/);
  assert.match(counted, /도구 3개를 받았어요/);
  assert.match(
    counted,
    /읽기만 하는 도구 1개는 한 번 승인한 뒤 계속 허락할 수 있어요/,
  );

  const noReadOnly = renderToStaticMarkup(
    loaded.StandardPluginTools({ listedTools: 2, readOnlyTools: 0 }),
  );
  assert.match(noReadOnly, /도구 2개를 받았어요/);
  assert.doesNotMatch(noReadOnly, /계속 허락/);

  const unchecked = renderToStaticMarkup(
    loaded.StandardPluginTools({ listedTools: null, readOnlyTools: null }),
  );
  assert.match(unchecked, /모든 도구를 쓸 때마다 확인해요/);
  assert.doesNotMatch(unchecked, /받았어요/);
});
