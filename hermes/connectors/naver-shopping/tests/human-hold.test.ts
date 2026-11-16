import { afterEach, expect, test } from "bun:test";
import { HUMAN_HELD_CLOSE } from "../src/cdp.ts";
import { SEARCH_STATE } from "../src/expressions.ts";
import { toolFailure } from "../src/errors.ts";
import { forgetLeftTabs, readInTab } from "../src/tab.ts";

/**
 * 사람이 「직접 할게요」 로 브라우저를 쥔 동안의 중계를 흉내 낸다(fos-assistant ADR-20261116 / browser-lock).
 * `http` 는 모든 HTTP 창구를 423 으로, `socket` 은 탭은 만들어 주고 붙은 연결을 첫 명령에서 4423 으로 닫는다.
 */
function startHeldGateway(mode: "http" | "socket") {
  const http: string[] = [];
  const server = Bun.serve({
    port: 0,
    fetch(request, srv) {
      const url = new URL(request.url);
      http.push(`${request.method} ${url.pathname}`);
      if (url.pathname.startsWith("/devtools/"))
        return srv.upgrade(request) ? undefined : new Response("", { status: 400 });
      if (mode === "http") return new Response("", { status: 423 });
      if (url.pathname === "/json/new")
        return Response.json({ id: "TAB1", webSocketDebuggerUrl: `ws://${url.host}/devtools/page/TAB1` });
      return new Response("", { status: 423 });
    },
    websocket: {
      message(socket) {
        socket.close(HUMAN_HELD_CLOSE, "human_held");
      },
    },
  });
  return { url: `http://127.0.0.1:${server.port}`, http, stop: () => server.stop(true) };
}

let gateway: ReturnType<typeof startHeldGateway> | null = null;
afterEach(() => {
  gateway?.stop();
  forgetLeftTabs();
});

function resultOf(error: unknown) {
  const failure = toolFailure(error) as { isError?: boolean; content: { text: string }[] };
  return { isError: failure.isError, body: JSON.parse(failure.content[0]!.text) };
}

test("탭을 열기 전에 중계가 423 이면 탭 없는 사람이 쓰는 중 넘기기 결과로 끝난다", async () => {
  gateway = startHeldGateway("http");
  const error = await readInTab(gateway.url, "https://search.shopping.naver.com/x", SEARCH_STATE, (raw) => raw, {
    timeoutMs: 5_000,
  }).catch((caught) => caught);

  const { isError, body } = resultOf(error);
  expect(isError).toBeUndefined();
  expect(body.status).toBe("HUMAN_ACTION_REQUIRED");
  expect(body.handoff).toEqual({ code: "NAVER_SHOPPING_BROWSER_IN_USE", need: "human_browser" });
  expect(body.message).toContain("돌려주기");
});

test("붙은 연결을 중계가 4423 으로 닫으면 그 탭을 남기고 탭이 든 넘기기로 끝나며, 다음 호출이 그 탭을 닫으려 한다", async () => {
  gateway = startHeldGateway("socket");
  const error = await readInTab(gateway.url, "https://search.shopping.naver.com/x", SEARCH_STATE, (raw) => raw, {
    timeoutMs: 5_000,
  }).catch((caught) => caught);

  expect(resultOf(error).body.handoff).toEqual({
    code: "NAVER_SHOPPING_BROWSER_IN_USE",
    need: "human_browser",
    tab: "TAB1",
  });
  expect(gateway.http).not.toContain("GET /json/close/TAB1");

  await readInTab(gateway.url, "https://search.shopping.naver.com/x", SEARCH_STATE, (raw) => raw, {
    timeoutMs: 5_000,
  }).catch(() => {});
  expect(gateway.http).toContain("GET /json/close/TAB1");
});
