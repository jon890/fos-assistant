import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { renderToStaticMarkup } from "../../web/node_modules/react-dom/server.js";
import * as connection from "../../web/src/lib/connection.ts";
import { toolText } from "../../web/src/lib/connector-tool-text.ts";
import ts from "../../web/node_modules/typescript/lib/typescript.js";

async function render(tools: connection.ConnectorTool[]) {
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
  const module = {
    exports: {} as {
      ConnectorTools: (props: {
        tools: connection.ConnectorTool[];
      }) => React.ReactNode;
    },
  };
  const dependencies: Record<string, unknown> = {
    "@/lib/connection": connection,
    "@/lib/connector-tool-text": { toolText },
    "@/components/ui/badge": {
      Badge: ({ children }: { children: React.ReactNode }) => children,
    },
  };
  vm.runInNewContext(compiled, {
    module,
    exports: module.exports,
    require: (name: string) => dependencies[name] ?? webRequire(name),
  });
  return renderToStaticMarkup(module.exports.ConnectorTools({ tools }));
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
