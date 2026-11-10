import { CdpSession, httpJson, wsUrlFor } from "./cdp.ts";
import { classifyPage, humanStepError } from "./classify.ts";
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
 * 새 탭 하나에서 주소를 열고 고정 식 하나로 상태 JSON 글을 꺼낸다.
 *
 * 순서는 빈 탭 열기, 붙기, 대화상자 처리기와 `Page.enable`, `Page.navigate`, 준비 상태, 화면 분류, 꺼내기다.
 * 처리기를 이동 전에 걸어 이동 중에 뜬 `alert`, `confirm` 도 닫기로 넘긴다. 이 탭은 사람이 쓰는 탭이 아니다.
 * 로그인·캡차·기기 인증 화면이면 아무것도 누르지 않고 그 탭을 남긴 채 그 종류의 오류로 끝난다. 사람이 그 탭에서 푼다.
 * 그 밖의 결과는 성공이든 실패든 그 탭을 닫는다. 다른 탭은 읽지도 닫지도 않는다.
 */
export async function readInTab(cdpUrl: string, url: string, expression: string, options: TabOptions = {}) {
  const deadline = Date.now() + (options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const remaining = () => {
    const left = deadline - Date.now();
    if (left <= 0) throw new ToolError("NAVER_SHOPPING_UNAVAILABLE");
    return left;
  };
  const pollMs = options.pollMs ?? DEFAULT_POLL_MS;
  const target = (await httpJson(cdpUrl, "/json/new?about:blank", { method: "PUT", timeoutMs: remaining() })) as {
    id?: unknown;
    webSocketDebuggerUrl?: unknown;
  } | null;
  const targetId = typeof target?.id === "string" ? target.id : "";
  if (!targetId || typeof target?.webSocketDebuggerUrl !== "string")
    throw new ToolError("NAVER_SHOPPING_BROWSER_UNREACHABLE");
  let keepTab = false;
  let session: CdpSession | null = null;
  try {
    session = await CdpSession.connect(wsUrlFor(cdpUrl, target.webSocketDebuggerUrl), remaining());
    const page = session;
    page.onEvent("Page.javascriptDialogOpening", () => {
      page.send("Page.handleJavaScriptDialog", { accept: false }).catch(() => {});
    });
    await page.send("Page.enable", {}, remaining());
    await page.send("Page.navigate", { url }, remaining());
    const evaluate = async (source: string) => {
      const { result } = await page.send<{ result?: { value?: unknown } }>(
        "Runtime.evaluate",
        { expression: source, returnByValue: true },
        remaining(),
      );
      return result?.value;
    };
    while ((await evaluate(READY_STATE)) !== "complete") await Bun.sleep(Math.min(pollMs, remaining()));
    const human = humanStepError(classifyPage(((await evaluate(PAGE_PROBE)) ?? {}) as { href?: unknown }));
    if (human !== null) {
      keepTab = true;
      throw new ToolError(human);
    }
    return await evaluate(expression);
  } finally {
    session?.close();
    if (!keepTab) await httpJson(cdpUrl, `/json/close/${encodeURIComponent(targetId)}`).catch(() => {});
  }
}
