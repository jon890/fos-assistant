import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import vm from "node:vm";
import ts from "../../web/node_modules/typescript/lib/typescript.js";
import * as connection from "../../web/src/lib/connection.ts";
import { AGENT_CODE_PATTERN } from "../../web/src/lib/agent.ts";

const SVG =
  "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciLz4=";
const PNG =
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";
const LINK = "https://example.com/service";

function summary(change: Record<string, unknown> = {}) {
  return {
    id: "demo-notes",
    title: "검사용 서비스",
    description: "서비스 설명",
    fields: [],
    tools: [],
    myStatus: "DISCONNECTED",
    available: true,
    bindings: [],
    ownerBrowserLoginUrl: null,
    ...change,
  };
}

/** 실제 GET 라우트와 응답 정제를 실행한다. 세션이 필요한 Control Plane 호출만 바꾼다. */
async function getCatalog(items: unknown[]): Promise<Response> {
  const webRequire = createRequire(
    new URL("../../web/package.json", import.meta.url),
  );
  const dependencies: Record<string, unknown> = {
    "@/lib/control-plane": {
      callControlPlane: async (path: string) => {
        assert.equal(path, "/api/v1/connectors");
        return { ok: true, status: 200, data: items };
      },
    },
    "@/lib/connection": connection,
    "@/lib/agent": { AGENT_CODE_PATTERN },
    "@/lib/json-body": {},
  };
  async function load(relative: string) {
    const source = await readFile(new URL(relative, import.meta.url), "utf8");
    const compiled = ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
      },
    }).outputText;
    const module = { exports: {} };
    vm.runInNewContext(compiled, {
      module,
      exports: module.exports,
      require: (name: string) => dependencies[name] ?? webRequire(name),
    });
    return module.exports;
  }
  dependencies["@/lib/connection-route"] = await load(
    "../../web/src/lib/connection-route.ts",
  );
  const route = (await load("../../web/src/app/api/connectors/route.ts")) as {
    GET: () => Promise<Response>;
  };
  return route.GET();
}

test("웹 카탈로그 라우트는 SVG와 PNG 아이콘, 링크를 유지하고 비밀 칸은 버린다", async () => {
  const response = await getCatalog([
    summary({ icon: SVG, link: LINK, secret: "옮기면 안 되는 값" }),
    summary({ icon: PNG, link: LINK }),
  ]);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [
    summary({ icon: SVG, link: LINK }),
    summary({ icon: PNG, link: LINK }),
  ]);
});

test("웹 카탈로그 라우트는 HTTPS 로그인 안내 주소를 유지하고 선언하지 않은 칸은 버린다", async () => {
  const loginUrl = "https://example.com/login?return_to=%2Fblog";
  const response = await getCatalog([
    summary({
      ownerBrowserLoginUrl: loginUrl,
      secret: "옮기면 안 되는 값",
      undeclared: "선언하지 않은 값",
    }),
  ]);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [
    summary({ icon: null, link: null, ownerBrowserLoginUrl: loginUrl }),
  ]);
});

test("로그인 안내 주소가 없거나 틀려도 카탈로그 행을 유지하고 주소만 null로 내려준다", async () => {
  const invalidUrls: unknown[] = [
    undefined,
    null,
    7,
    true,
    { url: LINK },
    [LINK],
    "",
    "주소 아님",
    "/login",
    "http://example.com/login",
    "javascript:alert(1)",
    "data:text/html,login",
    "https://user@example.com/login",
    "https://:password@example.com/login",
    "https://user:password@example.com/login",
  ];
  const response = await getCatalog([
    ...invalidUrls.map((ownerBrowserLoginUrl) =>
      summary({ icon: PNG, link: LINK, ownerBrowserLoginUrl }),
    ),
  ]);
  assert.equal(response.status, 200);
  assert.deepEqual(
    await response.json(),
    Array.from({ length: invalidUrls.length }, () =>
      summary({ icon: PNG, link: LINK, ownerBrowserLoginUrl: null }),
    ),
  );
});

test("아이콘과 링크, 로그인 안내 주소가 없는 옛 카탈로그도 세 칸을 null로 내려준다", async () => {
  const legacy = summary();
  Reflect.deleteProperty(legacy, "ownerBrowserLoginUrl");
  const response = await getCatalog([legacy]);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [
    summary({ icon: null, link: null }),
  ]);
});

test("장식 칸이 틀려도 커넥터는 유지하고 해당 칸만 비운다", async () => {
  const response = await getCatalog([
    summary({
      icon: "https://example.com/icon.png",
      link: "javascript:alert(1)",
    }),
    summary({ icon: { media_type: "image/png", data: "raw" }, link: 7 }),
    summary({ icon: PNG, link: "https://user:password@example.com/" }),
  ]);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), [
    summary({ icon: null, link: null }),
    summary({ icon: null, link: null }),
    summary({ icon: PNG, link: null }),
  ]);
});
