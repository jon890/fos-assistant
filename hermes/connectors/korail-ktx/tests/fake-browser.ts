/**
 * 중계 주소처럼 부르는 가짜 브라우저. `/json/version`, `/json/new`, `/json/close/<id>` 와 `devtools/` WebSocket 만 받는다.
 * 받은 HTTP 경로와 CDP 명령을 기록해 테스트가 무엇을 열고 무엇을 보냈는지 본다.
 * 자리 찾기(`DOM.performSearch`)는 `missing` 에 없는 자리를 모두 찾고, 「열차 조회」 를 누르면 목록 화면으로 넘어간다.
 */
import {
  EXPRESSIONS,
  LIST_STATE,
  PAGE_PROBE,
  READY_STATE,
  SUSEO_CHECKED,
} from "../src/expressions.ts";
import { LOOKUP_BUTTON } from "../src/fields.ts";

export type FakePage = {
  /** 처음 주소. 기본은 조건 화면이다. */
  href?: string;
  /** 「열차 조회」 를 누른 뒤 주소. 기본은 목록 화면이다. */
  afterLookup?: string;
  captcha?: boolean;
  /** 「안내 메시지」 층의 글. `afterLookupOnly` 면 목록 화면에서만 보인다. */
  notice?: string | null;
  noticeAfterLookupOnly?: boolean;
  /** 목록 꺼내기 식이 받을 값(JSON 글이나 null). */
  list?: string | null;
  suseoChecked?: boolean;
  /** 찾지 못하는 자리. */
  missing?: string[];
  navigateError?: string;
  /** 「열차 조회」 를 누르면 JS 대화상자를 띄운다. */
  dialogOnLookup?: string;
};

export type FakeBrowser = {
  url: string;
  http: string[];
  commands: { method: string; params: any }[];
  cookies: { name: string; domain: string }[];
  stop: () => void;
};

const TOKEN = "gateway-token-0123456789";

export function startFakeBrowser(page: FakePage = {}): FakeBrowser {
  const http: string[] = [];
  const commands: { method: string; params: any }[] = [];
  const cookies = [{ name: "JSESSIONID", domain: "www.korail.com" }];
  let href = page.href ?? "https://www.korail.com/ticket/search/general";
  let looked = false;
  let lastQuery = "";
  let nextSearch = 1;
  const searches = new Map<string, string>();
  const server = Bun.serve({
    port: 0,
    fetch(request, srv) {
      const url = new URL(request.url);
      const rest = url.pathname.replace(`/${TOKEN}`, "");
      http.push(`${request.method} ${rest}${url.search}`);
      const ws = `ws://${url.host}/${TOKEN}/devtools/page/tab-1`;
      if (rest.startsWith("/devtools/"))
        return srv.upgrade(request)
          ? undefined
          : new Response("", { status: 400 });
      if (rest === "/json/version")
        return Response.json({
          webSocketDebuggerUrl: `ws://${url.host}/${TOKEN}/devtools/browser/b`,
        });
      if (rest === "/json/new")
        return Response.json({ id: "tab-1", webSocketDebuggerUrl: ws });
      if (rest.startsWith("/json/close/"))
        return new Response("Target is closing");
      return new Response("", { status: 404 });
    },
    websocket: {
      message(socket, raw): void {
        const message = JSON.parse(String(raw));
        commands.push({ method: message.method, params: message.params });
        const reply = (result: unknown) => {
          socket.send(JSON.stringify({ id: message.id, result }));
        };
        const p = message.params ?? {};
        switch (message.method) {
          case "Storage.getCookies":
            return reply({ cookies });
          case "Page.navigate":
            return reply({
              frameId: "f",
              ...(page.navigateError ? { errorText: page.navigateError } : {}),
            });
          case "DOM.performSearch": {
            const searchId = `s${nextSearch++}`;
            searches.set(searchId, p.query);
            return reply({
              searchId,
              resultCount: (page.missing ?? []).includes(p.query) ? 0 : 1,
            });
          }
          case "DOM.getSearchResults":
            lastQuery = searches.get(p.searchId) ?? "";
            return reply({ nodeIds: [7] });
          case "DOM.getBoxModel":
            return reply({ model: { content: [0, 0, 10, 0, 10, 10, 0, 10] } });
          case "Input.dispatchMouseEvent":
            if (p.type === "mouseReleased" && lastQuery === LOOKUP_BUTTON) {
              looked = true;
              href =
                page.afterLookup ?? "https://www.korail.com/ticket/search/list";
              if (page.dialogOnLookup)
                socket.send(
                  JSON.stringify({
                    method: "Page.javascriptDialogOpening",
                    params: { type: "alert", message: page.dialogOnLookup },
                  }),
                );
            }
            return reply({});
          case "Runtime.evaluate": {
            const expression = p.expression;
            if (!EXPRESSIONS.has(expression)) {
              socket.send(
                JSON.stringify({
                  id: message.id,
                  error: { message: "unknown" },
                }),
              );
              return;
            }
            if (expression === READY_STATE)
              return reply({ result: { value: "complete" } });
            if (expression === PAGE_PROBE) {
              const notice =
                page.noticeAfterLookupOnly && !looked
                  ? null
                  : (page.notice ?? null);
              return reply({
                result: {
                  value: { href, captcha: page.captcha === true, notice },
                },
              });
            }
            if (expression === SUSEO_CHECKED)
              return reply({ result: { value: page.suseoChecked === true } });
            if (expression === LIST_STATE)
              return reply({ result: { value: page.list ?? null } });
            return reply({ result: {} });
          }
          default:
            return reply({});
        }
      },
    },
  });
  return {
    url: `http://127.0.0.1:${server.port}/${TOKEN}`,
    http,
    commands,
    cookies,
    stop: () => server.stop(true),
  };
}
