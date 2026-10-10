import {
  blockingPopup,
  clearProgress,
  dismissPopup,
  type EditorPage,
  requireClearScreen,
  TITLE_SELECTOR,
} from "./page.ts";

const LOGIN_HOST = "nid.naver.com";
const LOGIN_TITLE = "NAVER 로그인";
// 실제 제목은 `<블로그 이름> : 네이버 블로그` 라 들어 있는지로 본다.
const EDITOR_TITLE = "네이버 블로그";
const OPEN_TRIES = 40;

/**
 * 새 탭의 글쓰기 화면이 뜨기를 기다린다.
 * 로그인 화면으로 넘어가면 `login_required` 다. 캡차와 기기 인증도 같은 호스트 아래에 뜬다.
 * 「작성 중인 글이 있습니다」 알림은 「취소」 로 닫고, 다른 알림이 남아 있으면 멈춘다.
 * `keepDraftNotice` 면 그 알림을 닫지 않고 `"draft_notice"` 를 돌려준다. 「취소」 는 사용자가 쓰던 자동저장본을 버릴 수 있어
 * 승인 없이 부르는 읽기 도구는 닫지 않는다. 알림이 없으면 `"ready"` 다.
 */
export async function open(page: EditorPage, { keepDraftNotice = false } = {}) {
  const interval = page.times.openSeconds / OPEN_TRIES;
  for (let i = 0; i < OPEN_TRIES; i++) {
    await page.sleep(interval);
    const probe = JSON.parse(
      (await page.js<string>(
        "JSON.stringify({title: document.title, host: location.hostname," +
          ` ready: !!document.querySelector(${JSON.stringify(TITLE_SELECTOR)})})`,
      )) || "{}",
    ) as { title?: string; host?: string; ready?: boolean };
    const title = probe.title ?? "";
    if (title.includes(LOGIN_TITLE) || probe.host === LOGIN_HOST)
      throw page.fail("login_required", "로그인 화면으로 넘어갔다");
    if (!title.includes(EDITOR_TITLE) || !probe.ready) continue;

    let note = await blockingPopup(page);
    if (note.includes("작성 중인 글이 있습니다") && note.includes("이어서 작성하시겠습니까")) {
      if (keepDraftNotice) return "draft_notice";
      if (!(await dismissPopup(page, "취소")))
        throw page.fail("editor_failed", "작성 중인 글 알림을 닫지 못했다");
      note = await requireClearScreen(page);
    }
    if (note) throw page.fail("editor_failed", "알림이 떠 있어 멈춘다");
    // 존재 확인 뒤 알림을 처리하는 동안 문단이 교체되거나 숨겨질 수 있다.
    const ready = await page.js<boolean>(`(() => {
  const nodes = document.querySelectorAll(${JSON.stringify(TITLE_SELECTOR)});
  if (nodes.length !== 1) return false;
  const el = nodes[0];
  const r = el.getBoundingClientRect(), s = getComputedStyle(el);
  return el.isContentEditable && r.width > 0 && r.height > 0 &&
    s.visibility === "visible" && s.display !== "none" && Number(s.opacity) > 0;
})()`);
    if (!ready) continue;
    await clearProgress(page);
    return "ready";
  }
  throw page.fail("editor_failed", "글쓰기 화면이 뜨지 않았다");
}
