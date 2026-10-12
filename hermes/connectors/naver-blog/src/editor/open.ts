import { fieldCandidates } from "./fields.ts";
import {
  filterOpenDiagnostics,
  type OpenDiagnostics,
  type OpenReason,
} from "./open-diagnostics.ts";
import {
  blockingPopup,
  clearProgress,
  dismissPopup,
  type EditorPage,
  requireClearScreen,
  TITLE_SELECTOR,
} from "./page.ts";

const OPEN_TRIES = 40;

/** 원문 없이 최상위 문서와 프레임의 구조만 살핀다. 실행 문맥은 바꾸지 않는다. */
export async function probe(page: EditorPage, attempts: number) {
  const last = await page.js(`(() => {
  const openProbe = true;
  ${fieldCandidates(TITLE_SELECTOR, true)}
  const page = document.title.includes("NAVER 로그인") ? "login" :
    document.title.includes("네이버 블로그") ? "editor" : "other";
  const host = location.hostname === "nid.naver.com" ? "login" :
    location.hostname === "blog.naver.com" ? "blog" : "other";
  const frames = document.querySelectorAll('iframe, frame');
  let frameCandidates = 0;
  for (const frame of [...frames].slice(0, 100)) {
    try { frameCandidates += frame.contentDocument?.querySelectorAll(${JSON.stringify(TITLE_SELECTOR)}).length ?? 0; }
    catch { /* 다른 출처의 프레임은 읽지 않는다. */ }
  }
  const reason = page === "login" || host === "login" ? "login_required" :
    page !== "editor" ? "page_loading" : nodes.length > 100 || prepared.length > 1 ? "title_ambiguous" :
    !nodes.length ? (frameCandidates ? "frame_only" : "title_missing") : !el ? "title_unready" : "ready";
  return {reason, page, host, document_state: document.readyState,
    page_focused: document.hasFocus(), page_visible: document.visibilityState === "visible",
    candidates: nodes.length, prepared: prepared.length,
    sized: candidates.filter(c => c.sized).length, editable: candidates.filter(c => c.editable).length,
    visible: candidates.filter(c => c.visible).length, frames: frames.length, frame_candidates: frameCandidates,
    editors: editors.length, input_frames: inputFrames.length, buffer_ready: bufferReady};
})()`);
  return filterOpenDiagnostics({ attempts, last })!;
}

/** 새 탭의 유일한 준비 제목을 기다리고 읽기 경로의 작성 중인 글 알림은 보존한다. */
export async function open(page: EditorPage, { keepDraftNotice = false } = {}) {
  const interval = page.times.openSeconds / OPEN_TRIES;
  let diagnostics: OpenDiagnostics;
  const fail = (
    code: "login_required" | "editor_failed",
    message: string,
    reason?: OpenReason,
  ) => {
    if (reason) diagnostics.last.reason = reason;
    return page.fail(code, message, { open_diagnostics: diagnostics });
  };
  for (let i = 0; i < OPEN_TRIES; i++) {
    await page.sleep(interval);
    diagnostics = await probe(page, i + 1);
    if (diagnostics.last.reason === "login_required")
      throw fail("login_required", "로그인 화면으로 넘어갔다");
    if (diagnostics.last.page !== "editor") continue;

    // 제목이 없거나 숨겨져 있어도 읽기 경로의 복구 알림을 먼저 보존한다.
    let note = await blockingPopup(page);
    if (
      note.includes("작성 중인 글이 있습니다") &&
      note.includes("이어서 작성하시겠습니까")
    ) {
      if (keepDraftNotice) return "draft_notice";
      if (!(await dismissPopup(page, "취소")))
        throw fail(
          "editor_failed",
          "작성 중인 글 알림을 닫지 못했다",
          "draft_notice",
        );
      note = await requireClearScreen(page);
    }
    if (note) throw fail("editor_failed", "알림이 떠 있어 멈춘다", "popup");
    // 알림을 처리하는 동안 교체된 필드를 다시 판정한다.
    diagnostics = await probe(page, i + 1);
    if (diagnostics.last.reason === "login_required")
      throw fail("login_required", "로그인 화면으로 넘어갔다");
    if (diagnostics.last.reason !== "ready") continue;
    await clearProgress(page);
    return "ready";
  }
  throw fail("editor_failed", "글쓰기 화면이 뜨지 않았다");
}
