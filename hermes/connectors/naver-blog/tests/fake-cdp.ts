import type { ServerWebSocket } from "bun";

/** 브라우저가 준 오류 원문. 결과와 오류 글에 나오면 안 된다. */
export const upstreamText = "upstream-error-text-for-tests";

export type CdpCall = { target: string; method: string; params: any };
export type CdpHandler = (params: any, target: string) => unknown;

type SocketData = { target: string };

/** 바인딩 표식을 실은 중계 주소의 경로. 서명 자리는 시험용 16진수 64자다. */
export const GATEWAY_PATH = `/internal/browser-gateway/b1.${"ab".repeat(32)}`;

/**
 * 실제 Chrome 대신 Control Plane 중계처럼 디버깅 HTTP 창구와 WebSocket 명령에 답하는 대역이다.
 * 경로는 중계 주소의 경로(`prefix`) 아래만 받고, 중계처럼 `Origin` 머리가 있는 요청을 403 으로 거절한다.
 * 받은 CDP 메서드를 기록하고, 허용 목록 밖의 메서드는 위반으로 남긴다.
 */
export class FakeCdp {
  readonly calls: CdpCall[] = [];
  /** 접두사를 뗀 요청. `GET /json/version` 모양이다. */
  readonly httpRequests: string[] = [];
  /** 접두사를 포함한 요청 경로. WebSocket 연결 요청도 든다. */
  readonly paths: string[] = [];
  /** WebSocket 연결 요청마다 받은 `Origin` 머리. 없으면 빈 글이다. */
  readonly origins: string[] = [];
  readonly violations: string[] = [];
  /** `/json/new` 로 연 주소. */
  readonly opened: string[] = [];
  /** `/json/version` 이 돌려줄 `webSocketDebuggerUrl`. 비우면 이 대역의 주소를 쓴다. */
  debuggerUrl?: string;
  /** 참이면 HTTP 요청에 답하지 않는다. */
  hangHttp = false;
  private readonly handlers = new Map<string, CdpHandler>();
  private readonly allowed: Set<string>;
  private readonly targets = new Map<string, string>();
  private readonly sockets = new Map<string, ServerWebSocket<SocketData>>();
  private nextTarget = 1;
  /** 중계 주소의 경로. 끝 `/` 가 없다. */
  readonly prefix = GATEWAY_PATH;
  readonly server: ReturnType<typeof Bun.serve<SocketData, never>>;

  constructor({ allowed = ["Storage.getCookies"] }: { allowed?: string[] } = {}) {
    this.allowed = new Set(allowed);
    this.server = Bun.serve<SocketData, never>({
      hostname: "127.0.0.1",
      port: 0,
      fetch: (request, server) => this.http(request, server),
      websocket: {
        open: (socket) => {
          this.sockets.set(socket.data.target, socket);
        },
        message: (socket, message) => this.command(socket, String(message)),
      },
    });
  }

  /** 시험이 중계 주소 env 에 넣을 주소. 포트는 시험마다 운영체제가 고른다. */
  get url() {
    return `http://${this.server.hostname}:${this.server.port}${this.prefix}`;
  }

  /** 메서드마다 답을 정한다. 처리 함수가 던지면 CDP 오류로 답한다. */
  on(method: string, handler: CdpHandler) {
    this.handlers.set(method, handler);
  }

  /** 그 대상의 WebSocket 으로 이벤트 하나를 보낸다. 대상은 `page/<id>` 모양이다. */
  emit(target: string, method: string, params: unknown = {}) {
    this.sockets.get(target)?.send(JSON.stringify({ method, params }));
  }

  /** 시험이 미리 열어 둔 다른 탭. 커넥터가 닫으면 안 된다. */
  addTarget(page: string) {
    const id = `page-${this.nextTarget++}`;
    this.targets.set(id, page);
    return id;
  }

  /** 지금 열려 있는 탭의 id. */
  targetIds() {
    return [...this.targets.keys()];
  }

  methods() {
    return this.calls.map((call) => call.method);
  }

  stop() {
    this.server.stop(true);
  }

  private http(request: Request, server: Bun.Server<SocketData>) {
    const url = new URL(request.url);
    this.paths.push(url.pathname);
    if (!url.pathname.startsWith(`${this.prefix}/`)) {
      this.violations.push(`HTTP ${request.method} ${url.pathname}`);
      return new Response(null, { status: 404 });
    }
    const pathname = url.pathname.slice(this.prefix.length);
    this.httpRequests.push(`${request.method} ${pathname}`);
    const socketPath = /^\/devtools\/(browser|page)\/([A-Za-z0-9-]+)$/.exec(pathname);
    if (socketPath) {
      this.origins.push(request.headers.get("origin") ?? "");
      if (request.headers.has("origin")) return new Response(null, { status: 403 });
      if (server.upgrade(request, { data: { target: `${socketPath[1]}/${socketPath[2]}` } }))
        return undefined;
      return new Response("upgrade failed", { status: 400 });
    }
    if (this.hangHttp) return new Promise<Response>(() => {});
    const base = `ws://${this.server.hostname}:${this.server.port}${this.prefix}`;
    if (request.method === "GET" && pathname === "/json/version")
      return Response.json({
        Browser: "Chrome/0.0.0.0",
        webSocketDebuggerUrl:
          this.debuggerUrl ?? `${base}/devtools/browser/fake-browser`,
      });
    if (request.method === "GET" && pathname === "/json/list")
      return Response.json(
        [...this.targets].map(([id, page]) => ({
          id,
          type: "page",
          url: page,
          webSocketDebuggerUrl: `${base}/devtools/page/${id}`,
        })),
      );
    if (request.method === "PUT" && pathname === "/json/new") {
      const id = `page-${this.nextTarget++}`;
      const page = decodeURIComponent(url.search.slice(1));
      this.opened.push(page);
      this.targets.set(id, page);
      return Response.json({
        id,
        type: "page",
        url: page,
        webSocketDebuggerUrl: `${base}/devtools/page/${id}`,
      });
    }
    const close = /^\/json\/close\/([A-Za-z0-9-]+)$/.exec(pathname);
    if (request.method === "GET" && close) {
      this.targets.delete(close[1]!);
      return new Response("Target is closing");
    }
    this.violations.push(`HTTP ${request.method} ${pathname}`);
    return new Response(upstreamText, { status: 404 });
  }

  private command(socket: ServerWebSocket<SocketData>, raw: string) {
    const message = JSON.parse(raw) as { id: number; method: string; params?: any };
    const target = socket.data.target;
    this.calls.push({ target, method: message.method, params: message.params ?? {} });
    const reply = (body: Record<string, unknown>) =>
      socket.send(JSON.stringify({ id: message.id, ...body }));
    if (!this.allowed.has(message.method)) {
      this.violations.push(`CDP ${message.method}`);
      reply({ error: { code: -32601, message: upstreamText } });
      return;
    }
    const handler = this.handlers.get(message.method);
    // 답을 정하지 않은 메서드는 답하지 않는다. 시험이 시간 초과를 이렇게 만든다.
    if (!handler) return;
    try {
      reply({ result: handler(message.params ?? {}, target) ?? {} });
    } catch {
      reply({ error: { code: -32000, message: upstreamText } });
    }
  }
}

/** 이미 닫힌 포트의 중계 주소. 띄웠다 닫아 운영체제가 고른 포트를 쓴다. */
export function closedPortUrl() {
  const server = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: () => new Response("") });
  const url = `http://${server.hostname}:${server.port}${GATEWAY_PATH}`;
  server.stop(true);
  return url;
}

/** 편집기 단계가 쓰는 CDP 메서드. 이 목록 밖의 메서드가 오면 위반이다. */
export const EDITOR_METHODS = [
  "Page.enable",
  "Page.handleJavaScriptDialog",
  "Page.setInterceptFileChooserDialog",
  "Emulation.setDeviceMetricsOverride",
  "Runtime.enable",
  "Runtime.evaluate",
  "Runtime.callFunctionOn",
  "DOM.enable",
  "DOM.resolveNode",
  "Input.insertText",
  "Input.dispatchKeyEvent",
  "Input.dispatchMouseEvent",
];

export type FakePlace = { name: string; address: string };

const TITLE_SELECTOR = JSON.stringify(".se-documentTitle .se-text-paragraph");
const BODY_SELECTOR = JSON.stringify(".se-component.se-text .se-text-paragraph");

/** 식 안에서 처음 나오는 JSON 문자열 하나를 꺼낸다. */
function quoted(expression: string, after: string) {
  const start = expression.indexOf(after);
  const match = /"(?:[^"\\]|\\.)*"/.exec(expression.slice(start + after.length));
  return match ? (JSON.parse(match[0]) as string) : "";
}

/** 식 안에서 `[<숫자>]` 로 고른 순번을 꺼낸다. */
function indexIn(expression: string, after: string) {
  const start = expression.indexOf(after);
  const match = /\[(\d+)\]/.exec(expression.slice(start + after.length));
  return match ? Number(match[1]) : -1;
}

/**
 * SmartEditor 글쓰기 화면을 흉내 내는 탭 대역.
 * `Runtime.evaluate` 의 식을 조각으로 맞춰 화면 상태를 돌려주고, 마우스와 키, 글자 입력으로 상태를 바꾼다.
 * 시험은 칸을 바꿔 로그인 화면, 없는 카테고리, 겹친 장소, 늘지 않는 저장 수를 만든다.
 */
export class FakeEditor {
  docTitle = "가상블로그 : 네이버 블로그";
  host = "blog.naver.com";
  /** 화면을 덮은 알림의 글. 비면 알림이 없다. */
  popup = "";
  /** 붙자마자 alert 를 띄운다. */
  alertOnEnable = false;
  /** 사진 단추를 누르면 파일 선택 창 이벤트를 보낸다. */
  chooser = true;
  backendNodeId = 4242;
  title = "";
  body: string[] = [""];
  images: Array<{ fitted: boolean }> = [];
  stickers: string[] = [];
  maps: FakePlace[] = [];
  /** 스티커 창에 보이는 스티커 코드. */
  stickerCodes = ["ogq_abc123-4"];
  /** 검색어마다의 장소 검색 결과. */
  places: Record<string, FakePlace[]> = {};
  categories = ["가상국수로그", "일상"];
  category = "";
  tags: string[] = [];
  saved = 3;
  saveIncrements = true;
  /** 이 글자를 넣으면 화면에 들어가지 않는다. 빠진 줄을 만든다. */
  dropText?: string;
  /** 받은 `Runtime.evaluate` 식과 `Runtime.callFunctionOn` 함수. */
  readonly scripts: string[] = [];
  intercept = false;
  /** 발행 단추를 이만큼 눌러도 레이어가 열리지 않는다. 바쁜 편집기를 흉내 낸다. */
  publishIgnored = 0;
  /** 끝이 있는 애니메이션이 돈다고 이만큼 답한다. */
  animatingPolls = 0;
  /** 발행 단추 진단 식의 답. */
  publishDiagnosis = { publish_button: "shown", covered_by: null as string | null, layers: [] as string[] };
  /** 발행 단추를 누른 수. */
  publishClicks = 0;

  private readonly storage = new Map<string, string>();
  private focused: "title" | "body" | "search" | "tag" | null = null;
  private caret = 0;
  private selectAll = false;
  private stickerPanel = false;
  private mapOpen = false;
  private search = "";
  private results: FakePlace[] = [];
  private selectedResult = -1;
  private addClicked = false;
  private selectedImage = -1;
  private settingsOpen = false;
  private categoryListOpen = false;
  private tagInput = "";
  private readonly actions = new Map<number, () => void>();
  private nextSpot = 1;

  install(cdp: FakeCdp) {
    cdp.on("Page.enable", (_, target) => {
      if (this.alertOnEnable)
        cdp.emit(target, "Page.javascriptDialogOpening", { message: "확인", type: "alert" });
      return {};
    });
    cdp.on("Page.handleJavaScriptDialog", () => ({}));
    cdp.on("Emulation.setDeviceMetricsOverride", () => ({}));
    cdp.on("Runtime.enable", () => ({}));
    cdp.on("DOM.enable", () => ({}));
    cdp.on("Page.setInterceptFileChooserDialog", (params) => {
      this.intercept = params.enabled === true;
      return {};
    });
    cdp.on("DOM.resolveNode", (params) => ({
      object: { objectId: `file-input-${params.backendNodeId}` },
    }));
    cdp.on("Runtime.callFunctionOn", (params) => {
      this.scripts.push(String(params.functionDeclaration));
      this.images.push({ fitted: false });
      return { result: { type: "number", value: 1 } };
    });
    cdp.on("Runtime.evaluate", (params) => {
      const expression = String(params.expression);
      this.scripts.push(expression);
      const value = this.evaluate(expression);
      return { result: value === undefined ? { type: "undefined" } : { value } };
    });
    cdp.on("Input.insertText", (params) => {
      this.type(String(params.text));
      return {};
    });
    cdp.on("Input.dispatchKeyEvent", (params) => {
      if (params.type === "rawKeyDown") this.key(params.key, params.modifiers ?? 0);
      return {};
    });
    cdp.on("Input.dispatchMouseEvent", (params, target) => {
      if (params.type === "mouseReleased") {
        const action = this.actions.get(params.x);
        action?.();
        if (action === this.photoAction && this.chooser && this.intercept)
          cdp.emit(target, "Page.fileChooserOpened", {
            backendNodeId: this.backendNodeId,
            mode: "selectSingle",
          });
      }
      return {};
    });
  }

  private readonly photoAction = () => {};

  private readonly publishAction = () => {
    this.publishClicks += 1;
    if (this.publishIgnored > 0) {
      this.publishIgnored -= 1;
      return;
    }
    this.settingsOpen = !this.settingsOpen;
    this.categoryListOpen = false;
  };

  private spot(action: () => void) {
    const x = this.nextSpot++;
    this.actions.set(x, action);
    return x;
  }

  private type(text: string) {
    if (text === this.dropText) return;
    if (this.focused === "title") this.title += text;
    else if (this.focused === "body") this.body[this.caret] += text;
    else if (this.focused === "search") this.search += text;
    else if (this.focused === "tag") this.tagInput += text;
  }

  private key(key: string, modifiers: number) {
    if (key === "a" && modifiers === 2) {
      this.selectAll = true;
      return;
    }
    if (key === "Delete" && this.selectAll) {
      this.selectAll = false;
      if (this.focused === "title") this.title = "";
      else if (this.focused === "body") {
        this.body = [""];
        this.caret = 0;
      } else if (this.focused === "search") this.search = "";
      else if (this.focused === "tag") this.tagInput = "";
      return;
    }
    if (key === "Enter") {
      if (this.focused === "body") {
        this.body.splice(this.caret + 1, 0, "");
        this.caret += 1;
      } else if (this.focused === "search") {
        this.results = this.places[this.search] ?? [];
        this.selectedResult = -1;
      } else if (this.focused === "tag") {
        this.tags.push(this.tagInput);
        this.tagInput = "";
      }
      return;
    }
    if (key === "Backspace" && this.focused === "body") {
      const chars = [...this.body[this.caret]!];
      chars.pop();
      this.body[this.caret] = chars.join("");
    }
  }

  /** 마우스로 누를 요소를 식에서 알아보고 누르면 할 일을 정한다. 모르는 요소면 `null`. */
  private clickAction(finder: string): (() => void) | null {
    if (finder === `document.querySelector(${TITLE_SELECTOR})`)
      return () => (this.focused = "title");
    if (finder === `document.querySelector(${BODY_SELECTOR})`)
      return () => {
        this.focused = "body";
        this.caret = 0;
      };
    if (finder.includes(".se-popup button")) {
      if (!this.popup || quoted(finder, "===") !== "취소") return null;
      return () => (this.popup = "");
    }
    if (finder.includes("button.se-image-toolbar-button")) return this.photoAction;
    if (finder.includes(".se-module-image")) {
      const index = indexIn(finder, ".se-image')]");
      if (!this.images[index]) return null;
      return () => (this.selectedImage = index);
    }
    if (finder.includes("se-object-arrangement-fit-toolbar-button")) {
      if (this.selectedImage < 0) return null;
      return () => (this.images[this.selectedImage]!.fitted = true);
    }
    if (finder.includes("button.se-sticker-toolbar-button"))
      return () => (this.stickerPanel = true);
    if (finder.includes("button.se-sidebar-element-sticker")) {
      const code = quoted(finder, "===");
      if (!this.stickerPanel || !this.stickerCodes.includes(code)) return null;
      return () => this.stickers.push(code);
    }
    if (finder.includes("button.se-map-toolbar-button")) return () => (this.mapOpen = true);
    if (finder.includes("장소명을 입력하세요.")) {
      if (!this.mapOpen) return null;
      return () => (this.focused = "search");
    }
    if (finder.includes(".se-place-map-search-result-link")) {
      const index = indexIn(finder, ")");
      if (!this.results[index]) return null;
      return () => (this.selectedResult = index);
    }
    if (finder.includes(".se-place-add-button")) {
      if (this.selectedResult < 0) return null;
      return () => (this.addClicked = true);
    }
    if (finder.includes(".se-popup-button-confirm")) {
      if (!this.addClicked) return null;
      return () => {
        this.maps.push(this.results[this.selectedResult]!);
        this.mapOpen = false;
        this.addClicked = false;
        this.selectedResult = -1;
        this.results = [];
      };
    }
    if (finder.includes('data-click-area=\\"tpb.publish\\"')) return this.publishAction;
    if (finder.includes("label[for]")) {
      const name = quoted(finder, "===");
      if (!this.categoryListOpen || !this.categories.includes(name)) return null;
      return () => {
        this.category = name;
        this.categoryListOpen = false;
      };
    }
    if (finder.includes('document.querySelectorAll("button")') && quoted(finder, "===") === "저장")
      return () => {
        if (this.saveIncrements) this.saved += 1;
      };
    return null;
  }

  /** 식 하나에 화면 상태로 답한다. */
  private evaluate(expression: string): unknown {
    if (expression.includes("sessionStorage")) {
      const storage = {
        getItem: (key: string) => this.storage.get(key) ?? null,
        setItem: (key: string, value: string) => void this.storage.set(key, value),
        removeItem: (key: string) => void this.storage.delete(key),
      };
      return new Function("sessionStorage", `return (${expression});`)(storage);
    }
    if (expression.includes("location.hostname"))
      return JSON.stringify({
        title: this.docTitle,
        host: this.host,
        ready: this.host === "blog.naver.com",
      });
    if (expression.includes("JSON.stringify({x: r.left")) {
      const finder = /const el = ([\s\S]*?);\n {2}if \(!el\)/.exec(expression)?.[1] ?? "";
      const action = this.clickAction(finder);
      return action ? JSON.stringify({ x: this.spot(action), y: 1 }) : null;
    }
    if (expression.includes("createRange")) {
      const id = quoted(expression, "getElementById(");
      const index = Number(id.replace("p-", ""));
      if (this.body[index] === undefined) return null;
      return JSON.stringify({
        x: this.spot(() => {
          this.focused = "body";
          this.caret = index;
        }),
        y: 1,
      });
    }
    if (expression.includes("publish_button:")) return JSON.stringify(this.publishDiagnosis);
    if (expression.includes("getAnimations()")) {
      if (this.animatingPolls <= 0) return false;
      this.animatingPolls -= 1;
      return true;
    }
    if (expression.includes("elementFromPoint")) {
      const selector = quoted(expression, "querySelector(");
      let action: (() => void) | null = null;
      if (selector.includes("tpb.publish")) action = this.publishAction;
      if (selector.includes("tpb*i.category") && this.settingsOpen)
        action = () => (this.categoryListOpen = true);
      if (selector === "#tag-input" && this.settingsOpen) action = () => (this.focused = "tag");
      if (!action) return null;
      return JSON.stringify({ top: 10, x: this.spot(action), y: 1, uncovered: true });
    }
    if (expression.includes("popup-dim")) return this.popup;
    if (expression.includes("s.anchorOffset")) {
      const id = quoted(expression, "?.id ===");
      return this.focused === "body" && `p-${this.caret}` === id;
    }
    if (expression.includes("getSelection().anchorNode")) {
      const scope = quoted(expression, "closest(");
      if (scope === ".se-documentTitle") return this.focused === "title";
      if (scope === ".se-component.se-text") return this.focused === "body";
      return false;
    }
    if (expression.includes(".filter(e => { const value")) {
      const text = quoted(expression, "value ===");
      return JSON.stringify(
        this.body
          .map((line, index) => ({ id: `p-${index}`, text: line.trim() }))
          .filter(
            ({ text: value }) =>
              value === text || (text.startsWith(value) && value.length >= text.length - 10),
          ),
      );
    }
    if (expression.includes("getElementById(") && expression.endsWith("?.textContent")) {
      const id = quoted(expression, "getElementById(");
      return this.body[Number(id.replace("p-", ""))];
    }
    if (expression.includes("cloneNode")) {
      if (expression.includes(TITLE_SELECTOR)) return JSON.stringify([this.title]);
      if (expression.includes(BODY_SELECTOR)) return JSON.stringify(this.body);
      return "[]";
    }
    const count = /^document\.querySelectorAll\("\.se-component\.se-(\w+)"\)\.length$/.exec(
      expression,
    );
    if (count) {
      if (count[1] === "image") return this.images.length;
      if (count[1] === "sticker") return this.stickers.length;
      if (count[1] === "placesMap") return this.maps.length;
      return 0;
    }
    if (expression.includes(".se-component-content-fit').length"))
      return this.images.filter((image) => image.fitted).length;
    if (expression.includes("blogfiles.pstatic.net"))
      return Boolean(this.images[indexIn(expression, ".se-image')]")]);
    if (expression.includes("image.scrollIntoView"))
      return Boolean(this.images[indexIn(expression, ".se-image')]")]);
    if (expression.includes("querySelector('.se-component-content-fit') !== null"))
      return this.images[indexIn(expression, ".se-image')]")]?.fitted === true;
    if (expression.includes("se-object-arrangement-fit-toolbar-button"))
      return this.selectedImage >= 0;
    if (expression.includes("#image-type-list")) return false;
    if (expression.includes(".se-sidebar-container-sticker")) return this.stickerPanel;
    if (expression.includes("button.se-sidebar-element-sticker"))
      return this.stickerPanel && this.stickerCodes.includes(quoted(expression, "==="));
    if (expression.includes("sticker.querySelector('img')?.alt"))
      return this.stickers[indexIn(expression, ".se-sticker')]")] ?? "";
    if (expression.includes(".se-component.se-placesMap')]"))
      return JSON.stringify(this.maps.map((place) => `${place.name}\n${place.address}`));
    if (expression.includes(".se-popup-label-select-button"))
      return this.mapOpen ? "국내" : undefined;
    if (expression.includes("장소명을 입력하세요.")) return this.mapOpen;
    if (expression.includes("(link, index)"))
      return JSON.stringify(
        this.results.map((place, index) => ({ index, name: place.name, address: place.address })),
      );
    if (expression.includes(".se-place-add-button")) return this.selectedResult >= 0;
    if (expression.includes(".se-popup-button-confirm")) return this.addClicked;
    if (expression.includes("layer_publish")) return this.settingsOpen;
    if (expression.includes("tag-item-"))
      return JSON.stringify({
        category: this.category,
        tags: this.settingsOpen ? this.tags : [],
      });
    if (expression.includes("label[for]"))
      return this.categoryListOpen && this.categories.includes(quoted(expression, "==="));
    if (expression.includes("categoryItemText_"))
      return JSON.stringify(this.categoryListOpen ? this.categories : []);
    if (expression.includes("#tag-input")) return this.tagInput;
    if (expression.includes("임시저장된 글 보기")) return this.saved;
    if (expression.includes('document.querySelectorAll("button")'))
      return quoted(expression, "===") === "저장";
    return undefined;
  }
}
