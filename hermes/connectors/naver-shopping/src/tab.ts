import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { classifyPage, humanStepError, naverHost } from "./classify.ts";
import { ToolError } from "./errors.ts";
import { PAGE_PROBE, READY_STATE } from "./expressions.ts";

export type TabOptions = {
  /** 탭 열기부터 꺼내기까지의 전체 제한 시간. */
  timeoutMs?: number;
  /** 준비 상태를 다시 묻는 간격. */
  pollMs?: number;
};

const DEFAULT_TIMEOUT_MS = 45_000;
const DEFAULT_POLL_MS = 250;

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

/**
 * 새 탭 하나에서 주소를 열고 고정 식 하나로 상태 JSON 글을 꺼낸다.
 *
 * 순서는 빈 탭 열기, 붙기, 대화상자 처리기와 `Page.enable`, `Page.navigate`, 준비 상태, 화면 분류, 꺼내기다.
 * 처리기를 이동 전에 걸어 이동 중에 뜬 `alert`, `confirm` 도 닫기로 넘긴다. 이 탭은 사람이 쓰는 탭이 아니다.
 * 로그인·캡차·기기 인증 화면이면 아무것도 누르지 않고 그 탭을 남긴 채 그 종류의 오류로 끝난다. 사람이 그 탭에서 푼다.
 * 그 밖의 결과는 성공이든 실패든 그 탭을 닫는다. 다른 탭은 읽지 않고, 앞서 사람에게 넘기며 남긴 탭만 먼저 닫는다.
 * 남긴 탭은 오류의 `handoff` 칸에 싣는다. 최종 주소가 네이버가 아니면 읽지 않고 `NAVER_SHOPPING_PAGE_CHANGED` 다.
 */
export async function readInTab(
  cdpUrl: string,
  url: string,
  expression: string,
  options: TabOptions = {},
) {
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
  let session: CdpSession | null = null;
  try {
    session = await CdpSession.connect(
      wsUrlFor(cdpUrl, target.webSocketDebuggerUrl),
      remaining(),
    );
    const page = session;
    page.onEvent("Page.javascriptDialogOpening", () => {
      page
        .send("Page.handleJavaScriptDialog", { accept: false })
        .catch(() => {});
    });
    await page.send("Page.enable", {}, remaining());
    const nav = await page.send("Page.navigate", { url }, remaining());
    // 이동 자체가 실패하면(망 오류) Chrome 오류 화면도 준비 상태가 된다. 화면 바뀜이 아니라 일시 장애로 답한다.
    if (nav?.errorText) throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
    const evaluate = async (source: string) => {
      const { result } = await page.send<{ result?: { value?: unknown } }>(
        "Runtime.evaluate",
        { expression: source, returnByValue: true },
        remaining(),
      );
      return result?.value;
    };
    while ((await evaluate(READY_STATE)) !== "complete")
      await Bun.sleep(Math.min(pollMs, remaining()));
    const probe = ((await evaluate(PAGE_PROBE)) ?? {}) as {
      href?: unknown;
    };
    const kind = classifyPage(probe);
    const human = humanStepError(kind);
    if (human !== null && kind !== "ok") {
      keepTab = true;
      leftTabs.add(targetId);
      throw new ToolError(human, { need: kind, tab: targetId });
    }
    if (!naverHost(probe.href))
      throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
    return await evaluate(expression);
  } catch (error) {
    throw keepTab || Date.now() < deadline
      ? error
      : new ToolError("NAVER_SHOPPING_UNAVAILABLE");
  } finally {
    session?.close();
    if (!keepTab)
      await httpJson(
        cdpUrl,
        `/json/close/${encodeURIComponent(targetId)}`,
      ).catch(() => {});
  }
}
