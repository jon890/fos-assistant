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
 */
export async function open(page: EditorPage) {
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
      if (!(await dismissPopup(page, "취소")))
        throw page.fail("editor_failed", "작성 중인 글 알림을 닫지 못했다");
      note = await requireClearScreen(page);
    }
    if (note) throw page.fail("editor_failed", `알림이 떠 있어 멈춘다: ${note}`);
    await clearProgress(page);
    return;
  }
  throw page.fail("editor_failed", "글쓰기 화면이 뜨지 않았다");
}
