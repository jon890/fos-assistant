import { CdpSession, httpJson, wsUrlFor } from "../cdp.ts";
import { BLOG_ID_PATTERN } from "../session.ts";
import { EditorError, type EditorErrorCode } from "./editor-error.ts";

export { EditorError, type EditorErrorCode } from "./editor-error.ts";
export { focus, focusField } from "./focus.ts";
export {
  clearField,
  emojiSplit,
  normalize,
  paragraphs,
  typeLine,
  newParagraph,
  focusPlaceholder,
  componentCount,
} from "./text-fields.ts";

/**
 * 기다리는 시간의 상한(초). 기본값은 원본이 실측으로 정한 값이다.
 * 원본의 다른 대기 시간은 아래 넷의 기본값에 대한 비율로 두므로, 시험이 넷을 줄이면 같은 비율로 준다.
 */
export type EditorTimes = {
  /** 화면 하나가 바뀌기를 기다리는 기본 시간 */
  stepSeconds: number;
  /** 파일 선택 창과 사진이 본문에 들어오기를 기다리는 시간 */
  photoSeconds: number;
  /** 저장 단추를 누른 뒤 임시저장 수가 늘기를 기다리는 시간 */
  saveSeconds: number;
  /** 글쓰기 화면이 뜨기를 기다리는 시간 */
  openSeconds: number;
};

export const DEFAULT_TIMES: EditorTimes = {
  stepSeconds: 3,
  photoSeconds: 30,
  saveSeconds: 25,
  openSeconds: 40,
};

export type EditorOptions = Partial<EditorTimes> & { signal?: AbortSignal };

export const TITLE_SELECTOR = ".se-documentTitle .se-text-paragraph";
export const BODY_SELECTOR = ".se-component.se-text .se-text-paragraph";
const PROGRESS_KEY = "fos-naver-blog/editor-progress";
export const STAGES = ["fill", "photos", "components", "settings"] as const;
export type ProgressStage = (typeof STAGES)[number];

// 글자가 화면에 보인 뒤에도 편집기가 받아들이기까지 시간이 걸린다.
// 그 전에 Enter 를 누르면 새 문단이 생기지 않고 다음 줄이 앞 줄을 덮어쓴다.
// 실측으로 0.05초에서는 한 번에 1~2줄이 빠졌고 0.1초부터 빠지지 않았다. 여유를 둔다.
export const SETTLE_SECONDS = 0.15;
const POLL_MS = 50;
export const ABORTED = "aborted";

const q = (value: unknown) => JSON.stringify(value);

/** 탭 하나에 붙은 편집기 창구. 명령과 대기마다 중단 신호를 본다. */
export class EditorPage {
  /** 지금 단계. 실패와 중단이 이 이름으로 알린다. */
  stage = "open";

  private constructor(
    private readonly session: CdpSession,
    readonly times: EditorTimes,
    private readonly signal?: AbortSignal,
  ) {}

  /**
   * 탭의 WebSocket 에 붙어 Page 도메인을 켜고 대화 상자를 바로 수락하게 한다.
   * `confirm` 이나 `alert` 가 떠 있으면 페이지 실행이 멈춰 모든 명령이 시간 초과로 끝난다.
   * 큰 가상 창에서는 아래 문단에 보낸 마우스 이벤트가 편집기에 닿지 않아 화면 크기를 1280×720 으로 둔다.
   */
  static async attach(wsUrl: string, options: EditorOptions = {}) {
    const { signal, ...times } = options;
    const session = await CdpSession.connect(wsUrl);
    const page = new EditorPage(
      session,
      { ...DEFAULT_TIMES, ...times },
      signal,
    );
    session.onEvent("Page.javascriptDialogOpening", () => {
      session
        .send("Page.handleJavaScriptDialog", { accept: true })
        .catch(() => {});
    });
    try {
      await page.call("Page.enable");
      await page.call("Emulation.setDeviceMetricsOverride", {
        width: 1280,
        height: 720,
        deviceScaleFactor: 1,
        mobile: false,
      });
    } catch (error) {
      session.close();
      throw error;
    }
    return page;
  }

  /** 원본의 화면 대기 시간(초)을 `stepSeconds` 의 비율로 바꾼다. */
  step(seconds: number) {
    return (seconds * this.times.stepSeconds) / DEFAULT_TIMES.stepSeconds;
  }

  /** 원본의 사진 대기 시간(초)을 `photoSeconds` 의 비율로 바꾼다. */
  photoWait(seconds: number) {
    return (seconds * this.times.photoSeconds) / DEFAULT_TIMES.photoSeconds;
  }

  fail(
    code: EditorErrorCode,
    message: string,
    extra?: Record<string, unknown>,
  ) {
    return new EditorError(code, this.stage, message, extra);
  }

  throwIfAborted() {
    if (this.signal?.aborted) throw this.fail("editor_failed", ABORTED);
  }

  /** 중단 신호가 오면 기다리던 것을 버리고 `aborted` 로 끝낸다. */
  private untilAborted<T>(work: Promise<T>): Promise<T> {
    const signal = this.signal;
    if (!signal) return work;
    if (signal.aborted) {
      work.catch(() => {});
      throw this.fail("editor_failed", ABORTED);
    }
    let stop = () => {};
    const aborted = new Promise<never>((_, reject) => {
      stop = () => reject(this.fail("editor_failed", ABORTED));
      signal.addEventListener("abort", stop, { once: true });
    });
    return Promise.race([work, aborted]).finally(() =>
      signal.removeEventListener("abort", stop),
    );
  }

  /** CDP 명령 하나. 브라우저의 오류 원문은 버리고 메서드 이름만 남긴다. */
  async call<T = any>(
    method: string,
    params: Record<string, unknown> = {},
  ): Promise<T> {
    this.throwIfAborted();
    try {
      return await this.untilAborted(this.session.send<T>(method, params));
    } catch (error) {
      if (error instanceof EditorError) throw error;
      throw this.fail("editor_failed", `브라우저 명령이 실패했다: ${method}`);
    }
  }

  /** 페이지 안에서 식을 돌려 값을 받는다. 식이 예외로 끝나면 `editor_failed` 다. */
  async js<T = any>(expression: string): Promise<T> {
    const result = await this.call("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (result?.exceptionDetails)
      throw this.fail("editor_failed", "화면 스크립트가 예외로 끝났다");
    return result?.result?.value as T;
  }

  /** 그 자리를 사람이 누른 것처럼 마우스로 누른다. */
  async mouseAt(x: number, y: number) {
    for (const type of ["mousePressed", "mouseReleased"])
      await this.call("Input.dispatchMouseEvent", {
        type,
        x,
        y,
        button: "left",
        clickCount: 1,
      });
  }

  /**
   * JS 식이 찾은 요소의 가운데를 마우스로 누른다.
   * 편집기와 Chrome 은 JS 의 `.click()` 을 사람이 누른 것으로 보지 않는다.
   * 저장 단추와 사진 단추도 CDP 마우스 이벤트를 써야 동작했다.
   */
  async mouseClick(finder: string) {
    const box = await this.js<string | null>(`(() => {
  const el = ${finder};
  if (!el) return null;
  el.scrollIntoView({behavior: "instant", block: "center"});
  const r = el.getBoundingClientRect();
  if (!r.width || !r.height) return null;
  return JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2});
})()`);
    if (!box) return false;
    const spot = JSON.parse(box) as { x: number; y: number };
    await this.mouseAt(spot.x, spot.y);
    return true;
  }

  /**
   * 지금 초점이 있는 곳에 글자를 넣는다.
   * 편집기는 자체 입력 버퍼로 글자를 받아 `execCommand` 로는 들어가지 않는다.
   * `Input.insertText` 는 브라우저의 입력 경로를 그대로 지난다.
   */
  async insertText(text: string) {
    await this.call("Input.insertText", { text });
  }

  /** 키 하나를 누르고 뗀다. `modifiers` 는 CDP 의 비트값이다(2 는 Control). */
  async press(key: string, code: string, keyCode: number, modifiers = 0) {
    for (const type of ["rawKeyDown", "keyUp"])
      await this.call("Input.dispatchKeyEvent", {
        type,
        key,
        code,
        windowsVirtualKeyCode: keyCode,
        nativeVirtualKeyCode: keyCode,
        ...(modifiers ? { modifiers } : {}),
      });
  }

  enter() {
    return this.press("Enter", "Enter", 13);
  }

  /**
   * check 가 참이 될 때까지 짧게 거듭 본다.
   * 시간 안에 참이 되지 않으면 거짓을 돌려주고, 호출자가 화면 대조로 실패를 판정한다.
   */
  async waitUntil(
    check: () => boolean | Promise<boolean>,
    seconds = this.times.stepSeconds,
  ) {
    const deadline = performance.now() + seconds * 1000;
    while (true) {
      if (await check()) return true;
      if (performance.now() >= deadline) return false;
      await this.sleep(POLL_MS / 1000);
    }
  }

  sleep(seconds: number) {
    return this.untilAborted(
      new Promise<void>((resolve) =>
        setTimeout(resolve, Math.max(0, seconds * 1000)),
      ),
    );
  }

  /**
   * 이름이 같은 이벤트 하나를 기다리기 시작한다. 이벤트를 부르는 조작보다 먼저 건다.
   * 시간 안에 오지 않으면 `null` 이다.
   */
  expectEvent<T = any>(method: string, seconds: number): Promise<T | null> {
    const waiting = this.session
      .waitEvent<T>(method, Math.max(1, seconds * 1000))
      .catch(() => null);
    return this.untilAborted(waiting);
  }

  close() {
    this.session.close();
  }
}

/** 글쓰기 주소. 블로그 아이디는 연결 칸과 같은 모양만 받는다. */
export function writeUrl(blogId: string) {
  return `https://blog.naver.com/PostWriteForm.naver?blogId=${blogId}`;
}

/**
 * 그 블로그의 글쓰기 주소 하나만 새 탭으로 열고 그 탭에 붙는다.
 * 붙지 못하면 연 탭을 닫고 실패를 던진다. 다른 탭은 건드리지 않는다.
 */
export async function openWriteTab(
  cdpUrl: string,
  blogId: string,
  options: EditorOptions = {},
) {
  if (!BLOG_ID_PATTERN.test(blogId))
    throw new EditorError(
      "editor_failed",
      "open",
      "블로그 아이디의 모양이 맞지 않아 탭을 열지 않았다",
    );
  const target = (await httpJson(
    cdpUrl,
    `/json/new?${encodeURIComponent(writeUrl(blogId))}`,
    { method: "PUT" },
  )) as { id?: unknown; webSocketDebuggerUrl?: unknown } | null;
  const targetId = typeof target?.id === "string" ? target.id : "";
  if (!targetId)
    throw new EditorError("editor_failed", "open", "새 탭을 열지 못했다");
  try {
    const debuggerUrl = target?.webSocketDebuggerUrl;
    if (typeof debuggerUrl !== "string")
      throw new EditorError(
        "editor_failed",
        "open",
        "새 탭에 붙을 주소가 없다",
      );
    const page = await EditorPage.attach(
      wsUrlFor(cdpUrl, debuggerUrl),
      options,
    );
    return { targetId, page };
  } catch (error) {
    await closeTab(cdpUrl, targetId).catch(() => {});
    throw error;
  }
}

/** 그 탭 하나만 닫는다. Chrome 의 답은 JSON 이 아닌 글이다. */
export async function closeTab(cdpUrl: string, targetId: string) {
  await httpJson(cdpUrl, `/json/close/${encodeURIComponent(targetId)}`);
}

/** `scope` 안에서 글자가 정확히 `text` 인 단추를 찾는 JS 식. 클래스 이름에는 바뀌는 해시가 붙어 글자로 찾는다. */
export function buttonFinder(scope: string, text: string) {
  return `[...document.querySelectorAll(${q(scope)})].find(b => b.innerText.trim() === ${q(text)})`;
}

/** 선택자가 가리키는 자리를 눌러 초점을 준다. */
export function click(page: EditorPage, selector: string) {
  return page.mouseClick(`document.querySelector(${q(selector)})`);
}

export function clickButton(page: EditorPage, text: string) {
  return page.mouseClick(buttonFinder("button", text));
}

/** 화면을 덮고 있는 알림의 글. 없으면 빈 문자열이다. */
export async function blockingPopup(page: EditorPage) {
  return (
    (await page.js<string>(`(() => {
  const dim = [...document.querySelectorAll("[class*=popup-dim]")].find(e => {
    const r = e.getBoundingClientRect();
    const s = getComputedStyle(e);
    return r.width > 100 && s.display !== "none" && s.visibility !== "hidden";
  });
  if (!dim) return "";
  const box = document.querySelector(".se-popup-container, [role=dialog]");
  return box ? box.innerText.replace(/\\s+/g, " ").trim().slice(0, 120) : "알림";
})()`)) || ""
  );
}

/** 알림의 단추 하나를 마우스로 누른다. */
export function dismissPopup(page: EditorPage, button: string) {
  return page.mouseClick(
    buttonFinder(".se-popup button, [role=dialog] button", button),
  );
}

/** 화면을 덮은 알림이 잠깐 뒤에도 남아 있으면 그 글을 돌려준다. 없으면 빈 문자열이다. */
export async function requireClearScreen(page: EditorPage) {
  for (let i = 0; i < 3; i++) {
    if (!(await blockingPopup(page))) return "";
    await page.sleep(page.step(0.5));
  }
  return blockingPopup(page);
}

type Progress = { draftHash?: string; passed?: string[] };

/** 이 탭의 sessionStorage 에 둔 단계 결과. */
export async function progress(page: EditorPage): Promise<Progress> {
  const raw = await page.js<string | null>(
    `sessionStorage.getItem(${q(PROGRESS_KEY)})`,
  );
  try {
    return raw ? (JSON.parse(raw) as Progress) : {};
  } catch {
    return {};
  }
}

/** 같은 탭과 같은 초안의 단계 결과만 남긴다. 실패한 단계는 그 뒤 단계까지 지운다. */
export async function setStage(
  page: EditorPage,
  draftHash: string,
  stage: ProgressStage,
  passed: boolean,
) {
  let current = await progress(page);
  if (current.draftHash !== draftHash) current = { draftHash, passed: [] };
  const done = new Set(current.passed ?? []);
  if (passed) done.add(stage);
  else
    for (const later of STAGES.slice(STAGES.indexOf(stage))) done.delete(later);
  current.passed = STAGES.filter((item) => done.has(item));
  await page.js(`sessionStorage.setItem(${q(PROGRESS_KEY)}, ${q(q(current))})`);
}

/** 새 탭을 연 직후 이전 진행 표시를 지운다. */
export async function clearProgress(page: EditorPage) {
  await page.js(`sessionStorage.removeItem(${q(PROGRESS_KEY)})`);
}
