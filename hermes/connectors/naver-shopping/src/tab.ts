import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { classifyPage, humanStepError, naverHost } from "./classify.ts";
import { ToolError } from "./errors.ts";
import { GLOBAL_OBJECT, PAGE_PROBE, READY_STATE } from "./expressions.ts";

export type TabOptions = {
  /** 탭 열기부터 꺼내기까지의 전체 제한 시간. */
  timeoutMs?: number;
  /** 준비 상태를 다시 묻는 간격. */
  pollMs?: number;
  /** 옵션 드롭다운을 연 뒤 옵션이 실리기를 기다리는 시간. */
  optionWaitMs?: number;
};

const DEFAULT_TIMEOUT_MS = 45_000;
const DEFAULT_POLL_MS = 250;
const DEFAULT_OPTION_WAIT_MS = 5_000;

/**
 * 사람에게 넘기며 남긴 탭이다. 이 MCP 서버 프로세스의 메모리에만 있다.
 * 다음 탭 열기가 새 탭을 열기 전에 모두 닫는다. 다음 호출은 「다 했어요」 뒤의 새 turn 에서 오므로 사람의 일은 끝났다고 본다.
 * 프로세스가 다시 뜨면 목록이 사라지고, 그 탭은 사람이 닫을 때까지 남는다.
 */
const leftTabs = new Set<string>();

/** 테스트가 앞 테스트의 남긴 탭을 지운다. */
export function forgetLeftTabs() {
  leftTabs.clear();
}

/** 앞서 남긴 탭을 닫는다. 닫지 못해도 읽기는 잇는다. 사람이 이미 닫은 탭일 수 있다. */
async function closeLeftTabs(cdpUrl: string) {
  const tabs = [...leftTabs];
  leftTabs.clear();
  for (const id of tabs)
    await httpJson(cdpUrl, `/json/close/${encodeURIComponent(id)}`).catch(
      () => {},
    );
}

/** 탭 하나에 붙은 연결이다. 식은 `expressions.ts` 의 상수만 싣는다. */
export type Page = {
  readonly id: string;
  /** 상수 식 하나를 부르고 값을 돌려준다. */
  evaluate(source: string): Promise<unknown>;
  /** 상수 함수 선언 하나를 인자 값과 함께 부른다. 인자는 값으로만 넘어가 식을 정하지 못한다. */
  call(declaration: string, args: unknown[]): Promise<unknown>;
  /** 전체 제한 시간에서 남은 밀리초. 넘었으면 일시 장애로 던진다. */
  remaining(): number;
  /** 옵션 드롭다운을 연 뒤 옵션이 실리기를 기다리는 시간. */
  readonly optionWaitMs: number;
  /** 정상으로 끝나도 이 탭을 닫지 않는다. 주문서 탭처럼 사람에게 남길 때 부른다. */
  keep(): void;
  /** 그 탭을 주소로 옮긴다. 이동 응답의 망 오류 글이 있으면 `errorText` 로 준다. */
  navigate(url: string): Promise<{ errorText?: unknown } | undefined>;
  /** 붙은 연결을 닫는다. 탭은 닫지 않는다. */
  close(): void;
};

/** 브라우저 대상 하나에 붙는다. 대화상자를 닫기로 넘기는 처리기를 먼저 걸고 `Page.enable` 을 켠다. 우리가 연 탭만 붙는다. */
export async function attachPage(
  cdpUrl: string,
  target: { id: string; webSocketDebuggerUrl: string },
  remaining: () => number,
  onKeep: () => void = () => {},
  optionWaitMs = DEFAULT_OPTION_WAIT_MS,
): Promise<Page> {
  const session = await CdpSession.connect(
    wsUrlFor(cdpUrl, target.webSocketDebuggerUrl),
    remaining(),
  );
  session.onEvent("Page.javascriptDialogOpening", () => {
    session
      .send("Page.handleJavaScriptDialog", { accept: false })
      .catch(() => {});
  });
  try {
    await session.send("Page.enable", {}, remaining());
  } catch (error) {
    session.close();
    throw error;
  }
  const evaluate = async (source: string) => {
    const { result } = await session.send<{ result?: { value?: unknown } }>(
      "Runtime.evaluate",
      { expression: source, returnByValue: true },
      remaining(),
    );
    return result?.value;
  };
  return {
    id: target.id,
    evaluate,
    async call(declaration, args) {
      // 함수 선언을 부를 대상은 페이지의 전역 객체다. 이동하면 객체 id 가 바뀌어 부를 때마다 새로 받는다.
      const global = await session.send<{ result?: { objectId?: unknown } }>(
        "Runtime.evaluate",
        { expression: GLOBAL_OBJECT },
        remaining(),
      );
      const objectId = global.result?.objectId;
      if (typeof objectId !== "string")
        throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
      const { result } = await session.send<{ result?: { value?: unknown } }>(
        "Runtime.callFunctionOn",
        {
          functionDeclaration: declaration,
          objectId,
          arguments: args.map((value) => ({ value })),
          returnByValue: true,
        },
        remaining(),
      );
      return result?.value;
    },
    navigate: (url) => session.send("Page.navigate", { url }, remaining()),
    remaining,
    optionWaitMs,
    keep: onKeep,
    close: () => session.close(),
  };
}

/** `document.readyState` 가 `complete` 가 될 때까지 묻는다. */
export async function waitReady(page: Page, pollMs: number) {
  while ((await page.evaluate(READY_STATE)) !== "complete")
    await Bun.sleep(Math.min(pollMs, page.remaining()));
}

/**
 * 새 탭 하나에서 주소를 열고 `work` 를 부른다. 읽기 도구는 아래 `readInTab` 으로 부른다.
 *
 * 순서는 빈 탭 열기, 붙기, 대화상자 처리기와 `Page.enable`, `Page.navigate`, 준비 상태, 화면 분류, `work` 다.
 * 처리기를 이동 전에 걸어 이동 중에 뜬 `alert`, `confirm` 도 닫기로 넘긴다. 이 탭은 사람이 쓰는 탭이 아니다.
 * 로그인·캡차·기기 인증 화면이면 아무것도 누르지 않고 그 탭을 남긴 채 그 종류의 넘기기 결과(`isError` 없음)로 끝난다. 사람이 그 탭에서 푼다.
 * 최종 주소가 네이버가 아니거나 `work` 가 `NAVER_SHOPPING_PAGE_CHANGED` 를 내면(예상 밖 화면) 읽기를 멈추고 그 탭을 남긴 채
 * `unexpected_page` 넘기기로 끝난다. 사람이 그 화면을 보고 판단한다. 그래서 `work` 는 탭을 닫기 전에 부른다.
 * 그 밖의 결과는 성공이든 실패든 그 탭을 닫는다. `work` 가 `page.keep()` 을 불렀으면 정상으로 끝나도 남긴다.
 * 다른 탭은 읽지 않고, 앞서 사람에게 넘기며 남긴 탭만 먼저 닫는다.
 * 남긴 탭은 결과의 `handoff` 칸에 싣는다. 전체 제한 시간을 넘긴 실패는 탭을 닫고 일시 장애다.
 */
export async function workInTab<T>(
  cdpUrl: string,
  url: string,
  work: (page: Page) => Promise<T>,
  options: TabOptions = {},
): Promise<T> {
  const deadline = Date.now() + (options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const remaining = () => {
    const left = deadline - Date.now();
    if (left <= 0) throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
    return left;
  };
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
  await closeLeftTabs(cdpUrl);
  const target = (await httpJson(cdpUrl, "/json/new?about:blank", {
    method: "PUT",
    timeoutMs: remaining(),
  })) as {
    id?: unknown;
    webSocketDebuggerUrl?: unknown;
  } | null;
  const targetId = typeof target?.id === "string" ? target.id : "";
  if (!targetId || typeof target?.webSocketDebuggerUrl !== "string")
    throw new ToolError("NAVER_SHOPPING_BROWSER_UNREACHABLE");
  let keepTab = false;
  let handedOff = false;
  let page: Page | null = null;
  try {
    page = await attachPage(
      cdpUrl,
      { id: targetId, webSocketDebuggerUrl: target.webSocketDebuggerUrl },
      remaining,
      () => {
        keepTab = true;
      },
      options.optionWaitMs,
    );
    const nav = await page.navigate(url);
    // 이동 자체가 실패하면(망 오류) Chrome 오류 화면도 준비 상태가 된다. 화면 바뀜이 아니라 일시 장애로 답한다.
    if (nav?.errorText) throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
    await waitReady(page, pollMs);
    const probe = ((await page.evaluate(PAGE_PROBE)) ?? {}) as {
      href?: unknown;
    };
    const kind = classifyPage(probe);
    const human = humanStepError(kind);
    if (human !== null && kind !== "ok") {
      handedOff = true;
      leftTabs.add(targetId);
      throw new ToolError(human, { need: kind, tab: targetId });
    }
    if (!naverHost(probe.href))
      throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
    return await work(page);
  } catch (error) {
    if (handedOff) throw error;
    if (Date.now() >= deadline) {
      keepTab = false;
      throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
    }
    if (
      error instanceof ToolError &&
      error.code === "NAVER_SHOPPING_PAGE_CHANGED" &&
      !error.handoff
    ) {
      handedOff = true;
      leftTabs.add(targetId);
      throw new ToolError(error.code, {
        need: "unexpected_page",
        tab: targetId,
      });
    }
    keepTab = false;
    throw error;
  } finally {
    page?.close();
    if (!keepTab && !handedOff)
      await httpJson(
        cdpUrl,
        `/json/close/${encodeURIComponent(targetId)}`,
      ).catch(() => {});
  }
}

/**
 * 새 탭 하나에서 주소를 열고 고정 식 하나로 상태 JSON 글을 꺼내 `parse` 로 읽는다. 탭의 규칙은 `workInTab` 이 갖는다.
 * `parse` 는 탭이 열린 채 불리고 둘째 인자로 그 탭을 받는다. 상품 읽기가 옵션 드롭다운을 한 번 열 때 쓴다.
 */
export function readInTab<T>(
  cdpUrl: string,
  url: string,
  expression: string,
  parse: (raw: unknown, page?: Page) => T | Promise<T>,
  options: TabOptions = {},
): Promise<T> {
  return workInTab(
    cdpUrl,
    url,
    async (page) => parse(await page.evaluate(expression), page),
    options,
  );
}

/** 브라우저의 탭 목록(`/json/list` 의 `page`). 채우기가 「바로구매」 뒤 새로 열린 탭을 가릴 때만 쓴다. 탭 글은 읽지 않는다. */
export async function listTabs(cdpUrl: string, timeoutMs: number) {
  const listed = await httpJson(cdpUrl, "/json/list", { timeoutMs });
  return (Array.isArray(listed) ? listed : [])
    .filter(
      (item) =>
        item?.type === "page" &&
        typeof item.id === "string" &&
        typeof item.webSocketDebuggerUrl === "string",
    )
    .map((item) => ({
      id: item.id as string,
      url: typeof item.url === "string" ? (item.url as string) : "",
      webSocketDebuggerUrl: item.webSocketDebuggerUrl as string,
    }));
}

/** 우리가 연 탭 하나를 닫는다. 닫지 못해도 결과는 그대로 둔다. */
export async function closeTab(cdpUrl: string, id: string) {
  await httpJson(cdpUrl, `/json/close/${encodeURIComponent(id)}`).catch(
    () => {},
  );
}
