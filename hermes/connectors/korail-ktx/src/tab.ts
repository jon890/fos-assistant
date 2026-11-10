/**
 * 새 탭 하나에서 코레일 조회를 한 번 한다. 순서와 탭 닫기 규칙은 설계의 「화면을 다루는 방식」 이 갖는다.
 *
 * - 빈 탭을 열고 대화상자 처리기와 `Page.enable` 을 건 뒤 조건 화면으로 이동한다. 우리 탭의 JS 대화상자는 글을 남기고 닫는다
 * - 기다림은 `waitFor` 하나가 맡고, 기다리는 동안 매번 화면을 분류한다. 사람이 해야 하는 화면이면 바로 멈춘다
 * - 자리는 `DOM.performSearch` 로 찾고 누르기와 글 넣기는 `Input.*` 로 한다. 탭에서 돌리는 식은 expressions.ts 의 상수뿐이다
 * - 로그인·탐지·캡차 화면이면 탭을 남기고, 그 밖의 결과는 성공이든 실패든 탭을 닫는다. 다른 탭은 읽지도 닫지도 않는다
 * - 자동 재시도는 없다
 */
import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { classifyPage, pageError } from "./classify.ts";
import { logDiagnostic, ToolError } from "./errors.ts";
import { LIST_STATE, PAGE_PROBE, READY_STATE, SUSEO_CHECKED } from "./expressions.ts";
import { LIST_PATH, SEARCH_URL } from "./fields.ts";
import type { Query } from "./input.ts";
import { conditionSteps, listSteps, type Step } from "./steps.ts";

export type TabOptions = {
  /** 탭 열기부터 꺼내기까지의 전체 제한 시간. */
  timeoutMs?: number;
  /** 단계 하나가 자리를 기다리는 시간. 넘으면 화면이 바뀐 것으로 본다. */
  stepMs?: number;
  /** 다시 묻는 간격. 사람 속도 간격이 아니라 화면이 바뀌었는지 보는 간격이다. */
  pollMs?: number;
};

const TOOL = "search_trains";

class Tab {
  dialog: string | null = null;
  keep = false;
  constructor(
    readonly page: CdpSession,
    private readonly deadline: number,
    private readonly options: Required<TabOptions>,
  ) {}

  remaining() {
    const left = this.deadline - Date.now();
    if (left <= 0) throw new ToolError("KORAIL_UNAVAILABLE");
    return left;
  }

  send<T = any>(method: string, params: Record<string, unknown> = {}) {
    return this.page.send<T>(method, params, this.remaining());
  }

  async evaluate(expression: string) {
    const { result } = await this.send<{ result?: { value?: unknown } }>(
      "Runtime.evaluate",
      { expression, returnByValue: true },
    );
    return result?.value;
  }

  /** 화면을 분류해 사람이 해야 하는 화면이나 코레일의 거절이면 그 오류로 멈춘다. */
  async check() {
    if ((await this.evaluate(READY_STATE)) !== "complete") return false;
    const probe = ((await this.evaluate(PAGE_PROBE)) ?? {}) as Record<string, unknown>;
    const error = pageError(classifyPage({ ...probe, dialog: this.dialog }));
    if (error) {
      this.keep = error.human;
      throw new ToolError(error.code, error.message ?? null);
    }
    return true;
  }

  /** 화면이 준비되고 `found` 가 값을 낼 때까지 기다린다. 기다리는 내내 분류한다. `limitMs` 를 넘으면 `null` 이다. */
  async waitFor<T>(found: () => Promise<T | null>, limitMs: number): Promise<T | null> {
    const until = Date.now() + limitMs;
    for (;;) {
      if (await this.check()) {
        const value = await found();
        if (value !== null) return value;
      }
      if (Date.now() >= until) return null;
      await Bun.sleep(Math.min(this.options.pollMs, this.remaining()));
    }
  }

  /** 자리 하나의 가운데 좌표. 없거나 보이지 않으면 `null` 이다. */
  async locate(target: string): Promise<{ x: number; y: number } | null> {
    // 검색은 문서 노드를 받은 뒤에야 돈다. 화면이 바뀌면 노드 번호도 바뀌어 매번 다시 받는다.
    await this.send("DOM.getDocument", { depth: 0 });
    const search = await this.send<{ searchId: string; resultCount: number }>(
      "DOM.performSearch",
      { query: target },
    );
    try {
      if (!search.resultCount) return null;
      const { nodeIds } = await this.send<{ nodeIds: number[] }>("DOM.getSearchResults", {
        searchId: search.searchId,
        fromIndex: 0,
        toIndex: 1,
      });
      const nodeId = nodeIds[0];
      if (!nodeId) return null;
      await this.send("DOM.scrollIntoViewIfNeeded", { nodeId }).catch(() => {});
      const box = await this.send<{ model?: { content?: number[] } }>("DOM.getBoxModel", { nodeId }).catch(() => null);
      const quad = box?.model?.content;
      if (!quad || quad.length < 8) return null;
      const x = (quad[0]! + quad[2]! + quad[4]! + quad[6]!) / 4;
      const y = (quad[1]! + quad[3]! + quad[5]! + quad[7]!) / 4;
      return { x, y };
    } finally {
      await this.send("DOM.discardSearchResults", { searchId: search.searchId }).catch(() => {});
    }
  }

  async run(step: Step) {
    if (step.kind === "type") return this.send("Input.insertText", { text: step.text });
    if (step.kind === "select_all") {
      for (const type of ["keyDown", "keyUp"])
        await this.send("Input.dispatchKeyEvent", { type, key: "a", code: "KeyA", modifiers: 2, windowsVirtualKeyCode: 65 });
      return;
    }
    const point = await this.waitFor(() => this.locate(step.target), step.optional ? 0 : this.options.stepMs);
    if (point === null) {
      if (step.optional) return;
      logDiagnostic(TOOL, `step.${step.name}`);
      throw new ToolError("KORAIL_PAGE_CHANGED");
    }
    const base = { x: point.x, y: point.y, button: "left", clickCount: 1 };
    await this.send("Input.dispatchMouseEvent", { type: "mouseMoved", x: point.x, y: point.y });
    await this.send("Input.dispatchMouseEvent", { type: "mousePressed", ...base });
    await this.send("Input.dispatchMouseEvent", { type: "mouseReleased", ...base });
  }
}

/** 조건을 넣고 「열차 조회」 를 한 번 누른 뒤 목록 화면의 고르기를 하고 목록 JSON 글을 꺼낸다. */
export async function searchInTab(cdpUrl: string, query: Query, options: TabOptions = {}) {
  const settings: Required<TabOptions> = {
    timeoutMs: options.timeoutMs ?? 60_000,
    stepMs: options.stepMs ?? 10_000,
    pollMs: options.pollMs ?? 200,
  };
  const deadline = Date.now() + settings.timeoutMs;
  const target = (await httpJson(cdpUrl, "/json/new?about:blank", {
    method: "PUT",
    timeoutMs: Math.max(1, deadline - Date.now()),
  })) as { id?: unknown; webSocketDebuggerUrl?: unknown } | null;
  const targetId = typeof target?.id === "string" ? target.id : "";
  if (!targetId || typeof target?.webSocketDebuggerUrl !== "string")
    throw new ToolError("KORAIL_BROWSER_UNREACHABLE");
  let tab: Tab | null = null;
  let session: CdpSession | null = null;
  try {
    session = await CdpSession.connect(
      wsUrlFor(cdpUrl, target.webSocketDebuggerUrl),
      Math.max(1, deadline - Date.now()),
    );
    const current = new Tab(session, deadline, settings);
    tab = current;
    session.onEvent("Page.javascriptDialogOpening", (params) => {
      current.dialog = typeof params?.message === "string" ? params.message : "";
      current.page.send("Page.handleJavaScriptDialog", { accept: false }).catch(() => {});
    });
    await current.send("Page.enable");
    const nav = await current.send("Page.navigate", { url: SEARCH_URL });
    if (nav?.errorText) throw new ToolError("KORAIL_UNAVAILABLE");
    for (const step of conditionSteps(query)) await current.run(step);
    const onList = await current.waitFor(
      async () => {
        const probe = ((await current.evaluate(PAGE_PROBE)) ?? {}) as { href?: unknown };
        return typeof probe.href === "string" && new URL(probe.href).pathname.startsWith(LIST_PATH) ? true : null;
      },
      settings.stepMs,
    );
    if (onList === null) {
      logDiagnostic(TOOL, "list.open");
      throw new ToolError("KORAIL_PAGE_CHANGED");
    }
    const suseo = await current.evaluate(SUSEO_CHECKED);
    for (const step of listSteps(query, suseo === true)) await current.run(step);
    const raw = await current.waitFor(async () => {
      const value = await current.evaluate(LIST_STATE);
      return typeof value === "string" ? value : null;
    }, settings.stepMs);
    if (raw === null) {
      logDiagnostic(TOOL, "list.read", ["row"]);
      throw new ToolError("KORAIL_PAGE_CHANGED");
    }
    return raw;
  } catch (error) {
    if (error instanceof ToolError) throw error;
    throw new ToolError("KORAIL_UNAVAILABLE");
  } finally {
    session?.close();
    if (!tab?.keep)
      await httpJson(cdpUrl, `/json/close/${encodeURIComponent(targetId)}`).catch(() => {});
  }
}
