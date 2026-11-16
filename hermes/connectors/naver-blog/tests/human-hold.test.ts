import { afterEach, expect, test } from "bun:test";
import { CdpSession, HUMAN_HELD_CLOSE } from "../src/cdp.ts";
import { toolFailure } from "../src/errors.ts";
import { sessionStatus } from "../src/session.ts";

/**
 * 사람이 「직접 할게요」 로 브라우저를 쥔 동안의 중계를 흉내 낸다(fos-assistant ADR-20261116 / browser-lock).
 * 모든 HTTP 창구를 423 으로 답하고, WebSocket 은 받은 뒤 첫 명령에서 4423 으로 닫는다.
 */
function startHeldGateway() {
  const server = Bun.serve({
    port: 0,
    fetch(request, srv) {
      const url = new URL(request.url);
      if (url.pathname.includes("/devtools/"))
        return srv.upgrade(request) ? undefined : new Response("", { status: 400 });
      return new Response("", { status: 423 });
    },
    websocket: {
      message(socket) {
        socket.close(HUMAN_HELD_CLOSE, "human_held");
      },
    },
  });
  return { url: `http://127.0.0.1:${server.port}/gateway-token`, stop: () => server.stop(true) };
}

let gateway: ReturnType<typeof startHeldGateway> | null = null;
afterEach(() => {
  void gateway?.stop();
});

function resultOf(error: unknown) {
  const failure = toolFailure(error) as { isError?: boolean; content: { text: string }[] };
  return { isError: failure.isError, body: JSON.parse(failure.content[0]!.text) };
}

test("중계가 423 이면 로그인 확인이 탭 없는 사람이 쓰는 중 넘기기 결과로 끝난다", async () => {
  gateway = startHeldGateway();
  const error = await sessionStatus({ NAVER_BLOG_BROWSER_URL: gateway.url, NAVER_BLOG_ID: "blog1" }).catch(
    (caught) => caught,
  );

  const { isError, body } = resultOf(error);
  expect(isError).toBeUndefined();
  expect(body.status).toBe("HUMAN_ACTION_REQUIRED");
  expect(body.handoff).toEqual({ code: "NAVER_BLOG_BROWSER_IN_USE", need: "human_browser" });
});

test("탭 연결이 4423 으로 닫히면 기다리던 명령과 다음 명령이 그 탭이 든 넘기기 오류로 끝난다", async () => {
  gateway = startHeldGateway();
  const session = await CdpSession.connect(`${gateway.url.replace("http", "ws")}/devtools/page/TAB1`, 2_000);
  const error = await session.send("Page.enable", {}, 2_000).catch((caught) => caught);
  const next = await session.send("Page.enable", {}, 2_000).catch((caught) => caught);
  session.close();
  expect(resultOf(next).body.handoff.tab).toBe("TAB1");

  expect(resultOf(error).body.handoff).toEqual({
    code: "NAVER_BLOG_BROWSER_IN_USE",
    need: "human_browser",
    tab: "TAB1",
  });
});
