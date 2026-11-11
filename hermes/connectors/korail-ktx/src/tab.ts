/**
 * 새 탭 하나에서 코레일 조회를 한 번 한다. 순서와 탭 닫기 규칙은 설계의 「화면을 다루는 방식」 이 갖는다.
 *
 * - 빈 탭을 열고 대화상자 처리기와 `Page.enable` 을 건 뒤 조건 화면으로 이동한다. 우리 탭의 JS 대화상자는 글을 남기고 닫는다
 * - 기다림은 `waitFor` 하나가 맡고, 기다리는 동안 매번 화면을 분류한다. 사람이 해야 하는 화면이면 바로 멈춘다
 * - 자리는 `DOM.performSearch` 로 찾고 누르기와 글 넣기는 `Input.*` 로 한다. 탭에서 돌리는 식은 expressions.ts 의 상수뿐이다
 * - 로그인·탐지·보안 문자 화면이면 탭을 남기고, 그 밖의 결과는 성공이든 실패든 탭을 닫는다. 다른 탭은 읽지도 닫지도 않는다
 * - 자동 재시도는 없다
 */
import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { classifyPage, pageError } from "./classify.ts";
import { logDiagnostic, ToolError } from "./errors.ts";
import {
  LIST_STATE,
  PAGE_PROBE,
  READY_STATE,
  SUSEO_CHECKED,
} from "./expressions.ts";
import { LIST_PATH, SEARCH_URL } from "./fields.ts";
import type { Query } from "./input.ts";
import { conditionSteps, listSteps, type Step } from "./steps.ts";

export type TabOptions = {
  /** 탭 열기부터 꺼내기까지의 전체 제한 시간. */
  timeoutMs?: number;
  /** 필수 단계 하나가 자리를 기다리는 시간. 넘으면 화면이 바뀐 것으로 본다. */
  stepMs?: number;
  /** 생략할 수 있는 단계(공지 닫기, 역 고르기)가 자리를 기다리는 시간. 넘으면 건너뛴다. */
  optionalMs?: number;
  /** 다시 묻는 간격. 사람 속도 간격이 아니라 화면이 바뀌었는지 보는 간격이다. */
  pollMs?: number;
};

const TOOL = "search_trains";
/** 같은 자리의 사본 가운데 상자가 있는 첫 것을 고를 때 보는 수. */
const CANDIDATES = 10;
type Box = { content?: number[]; width?: number; height?: number };

class Tab {
  dialog: string | null = null;
  keep = false;
  /** 거절로 보인 화면을 한 번 더 보고 확정한다. 대화상자 뒤 로그인 화면으로 넘어가는 경우를 거절로 끝내지 않는다. */
  private suspect = false;
  constructor(
    readonly page: CdpSession,
    private readonly deadline: number,
    readonly options: Required<TabOptions>,
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

  /** 화면을 분류해 사람이 해야 하는 화면이나 코레일의 거절이면 그 오류로 멈춘다. 준비되지 않았으면 거짓이다. */
  async check() {
    if ((await this.evaluate(READY_STATE)) !== "complete") return false;
    const probe = ((await this.evaluate(PAGE_PROBE)) ?? {}) as Record<
      string,
      unknown
    >;
    const error = pageError(classifyPage({ ...probe, dialog: this.dialog }));
    if (error && !error.human && !this.suspect) {
      this.suspect = true;
      return false;
    }
    if (error) {
      this.keep = error.human;
      throw new ToolError(error.code, error.message ?? null);
    }
    this.suspect = false;
    return true;
  }

  /** 화면이 준비되고 `found` 가 값을 낼 때까지 기다린다. 기다리는 내내 분류한다. `limitMs` 를 넘으면 `null` 이다. */
  async waitFor<T>(
    found: () => Promise<T | null>,
    limitMs: number,
  ): Promise<T | null> {
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

  /** 자리의 사본 가운데 상자가 있는 첫 것의 가운데 좌표. 없으면 `null` 이다. */
  async locate(target: string): Promise<{ x: number; y: number } | null> {
    // 검색은 문서 노드를 받은 뒤에야 돈다. 화면이 바뀌면 노드 번호도 바뀌어 매번 다시 받는다.
    await this.send("DOM.getDocument", { depth: 0 });
    const search = await this.send<{ searchId: string; resultCount: number }>(
      "DOM.performSearch",
      { query: target },
    );
    try {
      if (!search.resultCount) return null;
      const { nodeIds } = await this.send<{ nodeIds: number[] }>(
        "DOM.getSearchResults",
        {
          searchId: search.searchId,
          fromIndex: 0,
          toIndex: Math.min(search.resultCount, CANDIDATES),
        },
      );
      for (const nodeId of nodeIds) {
        await this.send("DOM.scrollIntoViewIfNeeded", { nodeId }).catch(
          () => {},
        );
        const { model } = await this.send<{ model?: Box }>("DOM.getBoxModel", {
          nodeId,
        }).catch(() => ({ model: undefined }));
        const q = model?.content;
        if (!q || q.length < 8 || !model?.width || !model.height) continue;
        return {
          x: (q[0]! + q[2]! + q[4]! + q[6]!) / 4,
          y: (q[1]! + q[3]! + q[5]! + q[7]!) / 4,
        };
      }
      return null;
    } finally {
      await this.send("DOM.discardSearchResults", {
        searchId: search.searchId,
      }).catch(() => {});
    }
  }

  async run(step: Step) {
    if (step.kind === "type")
      return this.send("Input.insertText", { text: step.text });
    if (step.kind === "select_all") {
      for (const type of ["rawKeyDown", "keyUp"])
        await this.send("Input.dispatchKeyEvent", {
          type,
          key: "a",
          code: "KeyA",
          modifiers: 2,
          windowsVirtualKeyCode: 65,
          commands: type === "rawKeyDown" ? ["selectAll"] : [],
        });
      return;
    }
    const point = await this.waitFor(
      () => this.locate(step.target),
      step.optional ? this.options.optionalMs : this.options.stepMs,
    );
    if (point === null) {
      if (step.optional) return;
      logDiagnostic(TOOL, `step.${step.name}`);
      throw new ToolError(step.missing ?? "KORAIL_PAGE_CHANGED");
    }
    const base = { x: point.x, y: point.y, button: "left", clickCount: 1 };
    for (const type of ["mouseMoved", "mousePressed", "mouseReleased"])
      await this.send("Input.dispatchMouseEvent", {
        type,
        ...(type === "mouseMoved" ? { x: point.x, y: point.y } : base),
      });
  }

  /** 같은 값이 두 번 이어 나올 때까지 읽는다. 누른 뒤 다시 그려지기 전의 낡은 값을 돌려주지 않는다. */
  async stable(expression: string, limitMs: number) {
    let last: unknown = undefined;
    return this.waitFor(async () => {
      const value = await this.evaluate(expression);
      const same = value !== null && value !== undefined && value === last;
      last = value;
      return same ? value : null;
    }, limitMs);
  }
}

/** 조건을 넣고 「열차 조회」 를 한 번 누른 뒤 목록 화면의 고르기를 하고 목록 JSON 글을 꺼낸다. */
export async function searchInTab(
  cdpUrl: string,
  query: Query,
  options: TabOptions = {},
) {
  const settings: Required<TabOptions> = {
    timeoutMs: options.timeoutMs ?? 60_000,
    stepMs: options.stepMs ?? 10_000,
    optionalMs: options.optionalMs ?? 1_500,
    pollMs: options.pollMs ?? 200,
  };
  const deadline = Date.now() + settings.timeoutMs;
  const target = (await httpJson(cdpUrl, "/json/new?about:blank", {
    method: "PUT",
    timeoutMs: Math.max(1, deadline - Date.now()),
  })) as { id?: unknown; webSocketDebuggerUrl?: unknown } | null;
  const targetId = typeof target?.id === "string" ? target.id : "";
  let tab: Tab | null = null;
  let session: CdpSession | null = null;
  try {
    if (!targetId || typeof target?.webSocketDebuggerUrl !== "string")
      throw new ToolError("KORAIL_BROWSER_UNREACHABLE");
    session = await CdpSession.connect(
      wsUrlFor(cdpUrl, target.webSocketDebuggerUrl),
      Math.max(1, deadline - Date.now()),
    );
    const current = new Tab(session, deadline, settings);
    tab = current;
    session.onEvent("Page.javascriptDialogOpening", (params) => {
      current.dialog =
        typeof params?.message === "string" ? params.message : "";
      current.page
        .send("Page.handleJavaScriptDialog", { accept: false })
        .catch(() => {});
    });
    await current.send("Page.enable");
    const nav = await current.send("Page.navigate", { url: SEARCH_URL });
    if (nav?.errorText) throw new ToolError("KORAIL_UNAVAILABLE");
    for (const step of conditionSteps(query)) await current.run(step);
    const onList = await current.waitFor(async () => {
      const probe = ((await current.evaluate(PAGE_PROBE)) ?? {}) as {
        href?: unknown;
      };
      return typeof probe.href === "string" &&
        new URL(probe.href).pathname.startsWith(LIST_PATH)
        ? true
        : null;
    }, settings.stepMs);
    if (onList === null) {
      logDiagnostic(TOOL, "list.open");
      throw new ToolError("KORAIL_PAGE_CHANGED");
    }
    // 칸이 그려질 때까지 짧게 기다린다. 끝내 없으면 수서 함께 보기가 없는 구간이다.
    const suseo = await current.waitFor(async () => {
      const value = await current.evaluate(SUSEO_CHECKED);
      return typeof value === "boolean" ? value : null;
    }, settings.optionalMs);
    for (const step of listSteps(query, suseo)) await current.run(step);
    const raw = await current.stable(LIST_STATE, settings.stepMs);
    if (typeof raw !== "string") {
      logDiagnostic(TOOL, "list.read", ["row"]);
      throw new ToolError("KORAIL_PAGE_CHANGED");
    }
    return raw;
  } catch (error) {
    // 전체 제한 시간을 넘긴 뒤의 실패는 사람이 해야 하는 화면이 아니면 일시 장애로 알린다.
    if (!tab?.keep && Date.now() >= deadline)
      throw new ToolError("KORAIL_UNAVAILABLE");
    if (error instanceof ToolError) throw error;
    throw new ToolError("KORAIL_UNAVAILABLE");
  } finally {
    session?.close();
    if (targetId && !tab?.keep)
      await httpJson(
        cdpUrl,
        `/json/close/${encodeURIComponent(targetId)}`,
      ).catch(() => {});
  }
}
