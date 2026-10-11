import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { renderToStaticMarkup } from "../../web/node_modules/react-dom/server.js";
import * as connection from "../../web/src/lib/connection.ts";
import { toolDescription } from "../../web/src/lib/connector-tool-description.ts";
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
    "@/lib/connector-tool-description": { toolDescription },
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

test("실제 도구 컴포넌트는 설명을 HTML로 실행하지 않고 이름과 정책 밑에 평문으로 그린다", async () => {
  const html = await render([
    {
      name: "write",
      title: "쓰기",
      risk: "WRITE",
      approval: "REQUIRED",
      grant: false,
      description: "<script>alert(1)</script>\n**설명**\t입력",
    },
  ]);
  assert.ok(
    html.includes("&lt;script&gt;alert(1)&lt;/script&gt;\n**설명**\t입력"),
  );
  assert.ok(!html.includes("<script>"));
  assert.ok(
    html.indexOf("실행할 때마다 물어봐요") <
      html.indexOf("connector-tool-description"),
  );
});

test("옛 도구와 빈 설명·불량 설명은 기존 정책 배지만 보이고 빈 목록 안내를 유지한다", async () => {
  for (const description of [undefined, null, "", "글\u202e", "\ud800"]) {
    const html = await render([
      {
        name: "read",
        title: null,
        risk: "READ",
        approval: "NONE",
        grant: false,
        description,
      },
    ]);
    assert.ok(!html.includes("connector-tool-description"));
    assert.ok(html.includes("바로 실행해요"));
  }
  assert.ok((await render([])).includes("connector-tools-empty"));
});
