/**
 * 중계 주소처럼 부르는 가짜 브라우저. `/json/version`, `/json/new`, `/json/close/<id>` 와 `devtools/` WebSocket 만 받는다.
 * 받은 HTTP 경로와 CDP 명령을 기록해 테스트가 무엇을 열고 무엇을 보냈는지 본다.
 */
import { EXPRESSIONS, PAGE_PROBE, READY_STATE } from "../src/expressions.ts";

export type FakePage = {
  /** 이동한 뒤 `location.href`. */
  href?: string;
  captcha?: boolean;
  /** 상태를 꺼내는 식이 받을 값(JSON 글이나 null). */
  state?: string | null;
  /** 이동 뒤 `confirm` 같은 대화상자를 띄운다. */
  dialog?: boolean;
  /** 준비 상태가 `complete` 가 되기 전 `loading` 을 몇 번 답하는지. */
  loadingPolls?: number;
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
  const cookies = [
    { name: "NID_AUT", domain: ".naver.com" },
    { name: "NID_SES", domain: ".naver.com" },
  ];
  let polls = 0;
  const server = Bun.serve({
    port: 0,
    fetch(request, srv) {
      const url = new URL(request.url);
      const rest = url.pathname.replace(`/${TOKEN}`, "");
      http.push(`${request.method} ${rest}${url.search}`);
      const ws = `ws://${url.host}/${TOKEN}/devtools/page/tab-1`;
      if (rest.startsWith("/devtools/")) return srv.upgrade(request) ? undefined : new Response("", { status: 400 });
      if (rest === "/json/version") return Response.json({ webSocketDebuggerUrl: `ws://${url.host}/${TOKEN}/devtools/browser/b` });
      if (rest === "/json/new") return Response.json({ id: "tab-1", webSocketDebuggerUrl: ws });
      if (rest.startsWith("/json/close/")) return new Response("Target is closing");
      return new Response("", { status: 404 });
    },
    websocket: {
      message(socket, raw): void {
        const message = JSON.parse(String(raw));
        commands.push({ method: message.method, params: message.params });
        const reply = (result: unknown) => {
          socket.send(JSON.stringify({ id: message.id, result }));
        };
        if (message.method === "Storage.getCookies") return reply({ cookies });
        if (message.method === "Page.navigate") {
          reply({ frameId: "f" });
          if (page.dialog) socket.send(JSON.stringify({ method: "Page.javascriptDialogOpening", params: { type: "confirm" } }));
          return;
        }
        if (message.method === "Runtime.evaluate") {
          const expression = message.params.expression;
          if (!EXPRESSIONS.has(expression)) {
            socket.send(JSON.stringify({ id: message.id, error: { message: "unknown" } }));
            return;
          }
          if (expression === READY_STATE)
            return reply({ result: { value: polls++ < (page.loadingPolls ?? 0) ? "loading" : "complete" } });
          if (expression === PAGE_PROBE)
            return reply({ result: { value: { href: page.href ?? "https://search.shopping.naver.com/ns/search", captcha: page.captcha === true } } });
          return reply({ result: { value: page.state ?? null } });
        }
        reply({});
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
