import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EditorPage, openWriteTab, type EditorOptions } from "../src/editor/page.ts";
import { CdpSession, httpJson, wsUrlFor } from "../src/cdp.ts";

const HTML = `<!doctype html><meta charset="utf-8"><title>네이버 블로그 · 합성 화면</title>
<style>body{margin:40px}.se-text-paragraph{min-height:40px;width:500px;border:1px solid;padding:10px}
.se-component{margin-top:30px}#outside{margin-top:40px}</style>
<div class="se-documentTitle"><p class="se-text-paragraph" contenteditable="true" id="title"><br></p></div>
<div class="se-component se-text"><p class="se-text-paragraph" contenteditable="true" id="body"><br></p></div>
<div id="outside">synthetic-private-dom-marker</div>`;

/** 전용 임시 profile과 합성 HTML만 사용한다. 실제 글쓰기 주소는 로컬 fixture로 바꾼다. */
export async function localChrome() {
  const binary = process.env.FOS_TEST_CHROME || [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
  ].find(existsSync);
  if (!binary || !existsSync(binary)) throw new Error("실제 Chrome이 없어 합성 DOM 회귀를 실행하지 못했다");
  const profile = await mkdtemp(join(tmpdir(), "fos-synthetic-chrome-"));
  const proc = Bun.spawn([binary, "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
    "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
    "--disable-sync", "--disable-extensions", "--remote-debugging-address=127.0.0.1",
    "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"],
    { stdout: "ignore", stderr: "ignore" });
  let chromeUrl = "";
  const calls: string[] = [], closed: string[] = [];
  const pages = new Set<EditorPage>();
  type SocketData = { upstream: WebSocket; pending: string[] };
  let server: ReturnType<typeof Bun.serve<SocketData>> | undefined;
  const originalSend = CdpSession.prototype.send;
  const trackedSend: typeof originalSend = function <T>(this: CdpSession, method: string,
    params?: Record<string, unknown>, timeoutMs?: number): Promise<T> {
    calls.push(method);
    return originalSend.call(this, method, params, timeoutMs) as Promise<T>;
  };
  CdpSession.prototype.send = trackedSend;
  const stop = async () => {
    for (const page of pages) page.close();
    server?.stop(true);
    proc.kill("SIGKILL");
    await proc.exited;
    await rm(profile, { recursive: true, force: true });
    if (CdpSession.prototype.send === trackedSend) CdpSession.prototype.send = originalSend;
  };
  try {
    const deadline = performance.now() + 10_000;
    while (performance.now() < deadline) {
      const port = (await readFile(join(profile, "DevToolsActivePort"), "utf8").catch(() => "")).split("\n")[0];
      if (port && /^\d+$/.test(port)) { chromeUrl = `http://127.0.0.1:${port}`; break; }
      if (proc.exitCode !== null) throw new Error("합성 Chrome 시작 실패");
      await Bun.sleep(25);
    }
    if (!chromeUrl) throw new Error("합성 Chrome 시작 시간 초과");
    server = Bun.serve<SocketData>({
      hostname: "127.0.0.1", port: 0,
      async fetch(request, relay) {
        const incoming = new URL(request.url);
        incoming.pathname = incoming.pathname.replace(/^\/synthetic-gateway(?=\/)/, "");
        if (incoming.pathname === "/fixture") return new Response(HTML, { headers: { "content-type": "text/html" } });
        if (incoming.pathname.startsWith("/devtools/")) {
          const upstream = new WebSocket(chromeUrl.replace("http:", "ws:") + incoming.pathname);
          if (relay.upgrade(request, { data: { upstream, pending: [] } })) return;
          upstream.close();
          return new Response(null, { status: 400 });
        }
        let endpoint = incoming.pathname + incoming.search;
        if (incoming.pathname === "/json/new") {
          endpoint = `/json/new?${encodeURIComponent(`http://127.0.0.1:${relay.port}/fixture`)}`;
        } else if (incoming.pathname.startsWith("/json/close/")) {
          closed.push(incoming.pathname.slice("/json/close/".length));
        } else if (incoming.pathname !== "/json/version" && incoming.pathname !== "/json/list") {
          return new Response(null, { status: 404 });
        }
        const upstream = await fetch(chromeUrl + endpoint, { method: request.method });
        const text = await upstream.text();
        let result: any;
        try { result = JSON.parse(text); }
        catch { return new Response(text, { status: upstream.status }); }
        const prefixDebugger = (item: any) => {
          if (typeof item?.webSocketDebuggerUrl === "string") {
            const address = new URL(item.webSocketDebuggerUrl);
            address.pathname = "/synthetic-gateway" + address.pathname;
            item.webSocketDebuggerUrl = address.href;
          }
          return item;
        };
        return Response.json(Array.isArray(result) ? result.map(prefixDebugger) : prefixDebugger(result),
          { status: upstream.status });
      },
      websocket: {
        open(socket) {
          const { upstream, pending } = socket.data;
          const flush = () => { for (const message of pending.splice(0)) upstream.send(message); };
          if (upstream.readyState === WebSocket.OPEN) flush();
          else upstream.addEventListener("open", flush, { once: true });
          upstream.addEventListener("message", (event) => socket.send(String(event.data)));
          upstream.addEventListener("close", () => socket.close());
          upstream.addEventListener("error", () => socket.close());
        },
        message(socket, raw) {
          const message = String(raw);
          const { upstream, pending } = socket.data;
          if (upstream.readyState === WebSocket.OPEN) upstream.send(message);
          else pending.push(message);
        },
        close(socket) { socket.data.upstream.close(); },
      },
    });
    const base = `http://127.0.0.1:${server.port}`;
    const url = base + "/synthetic-gateway";
    const version = (await httpJson(url, "/json/version") as { Browser: string }).Browser;
    return {
      url, version, calls, closed, stop,
      async latest() {
        const targets = await httpJson(url, "/json/list") as Array<{ url: string; webSocketDebuggerUrl: string }>;
        const target = targets.find((item) => item.url === `${base}/fixture`);
        if (!target) throw new Error("합성 탭을 찾지 못했다");
        const page = await EditorPage.attach(wsUrlFor(url, target.webSocketDebuggerUrl), { stepSeconds: 0.6 });
        pages.add(page);
        return page;
      },
      async tab(options: EditorOptions = {}, direct = false) {
        const settings = { stepSeconds: 0.6, openSeconds: 0.5, ...options };
        const target = direct ? await httpJson(chromeUrl,
          `/json/new?${encodeURIComponent(`${base}/fixture`)}`, { method: "PUT" }) as
          { id: string; webSocketDebuggerUrl: string } : null;
        const result = target ? {
          targetId: target.id,
          page: await EditorPage.attach(wsUrlFor(chromeUrl, target.webSocketDebuggerUrl), settings),
        } : await openWriteTab(url, "synthetic-blog", settings);
        pages.add(result.page);
        // Chrome 탐색은 CDP 연결과 별개로 끝난다. 합성 문단이 뜬 뒤 테스트에 넘긴다.
        if (!(await result.page.waitUntil(() => result.page.js("!!document.querySelector('#title')"), 2)))
          throw new Error("합성 DOM 준비 실패");
        return result;
      },
    };
  } catch (error) {
    await stop();
    throw error;
  }
}
