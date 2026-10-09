import type { EditorDocument } from "../document.ts";
import { ToolError } from "../errors.ts";
import { blockingPopup, type EditorPage, normalize } from "./page.ts";

/** 임시저장 목록 단추. 이 단추의 aria-label 에 임시저장 수가 있다. */
const LIST_BUTTON = '[aria-label*="임시저장된 글 보기"]';
/** 열린 임시저장 목록의 글 단추. 목록 API 와 같은 차례로 놓인다(실측). */
const LIST_ITEM = 'button[data-click-area="tpb*s.tlist"]';
const LIST_CLOSE = 'button[data-click-area="tpb*s.close"]';
const LIST_MAX = 200;
export const DRAFT_ID_PATTERN = /^[1-9][0-9]{0,19}$/;

const q = (value: unknown) => JSON.stringify(value);

export type TempDraft = { draft_id: string; title: string; saved_at: string | null };

/**
 * 편집기가 임시저장 목록을 열 때 부르는 목록 API 를 같은 탭에서 부른다(실측).
 * 응답은 앞에 줄바꿈이 붙은 JSON 이고 `result.tempPostList` 의 `logNo`, `title`, `modiDate` 를 쓴다.
 * 모양이 다르면 `editor_failed` 다. 응답의 글은 싣지 않는다.
 */
export async function tempDrafts(page: EditorPage, blogId: string): Promise<TempDraft[]> {
  const raw = await page.js<string>(`(async () => {
  const response = await fetch(${q(`/TempPostList.naver?blogId=${encodeURIComponent(blogId)}&editorVersion=4&onlyCount=false`)}, {credentials: "include"});
  return JSON.stringify({status: response.status, text: await response.text()});
})()`);
  let list: unknown;
  try {
    const { status, text } = JSON.parse(raw || "{}") as { status?: number; text?: string };
    const body = JSON.parse((text ?? "").trim()) as {
      isSuccess?: unknown;
      result?: { tempPostList?: unknown };
    };
    if (status !== 200 || body.isSuccess !== true) throw new Error("목록 응답이 실패다");
    list = body.result?.tempPostList;
  } catch {
    throw page.fail("editor_failed", "임시저장 목록 응답을 읽지 못했다");
  }
  if (!Array.isArray(list)) throw page.fail("editor_failed", "임시저장 목록 응답의 모양이 다르다");
  return list.slice(0, LIST_MAX).map((item) => {
    const { logNo, title, modiDate } = (item ?? {}) as Record<string, unknown>;
    const id = typeof logNo === "number" && Number.isSafeInteger(logNo) ? String(logNo) : "";
    if (!DRAFT_ID_PATTERN.test(id) || typeof title !== "string")
      throw page.fail("editor_failed", "임시저장 목록 응답의 모양이 다르다");
    const saved = typeof modiDate === "number" && Number.isFinite(modiDate) ? new Date(modiDate) : null;
    return {
      draft_id: id,
      title,
      saved_at: saved && !Number.isNaN(saved.getTime()) ? saved.toISOString() : null,
    };
  });
}

/** 편집기의 문서를 읽는다. 편집기 객체나 문서가 없으면 `editor_failed` 다. */
export async function editorDocument(page: EditorPage): Promise<EditorDocument> {
  const raw = await page.js<string | null>(
    "(() => { const editors = window.SmartEditor && window.SmartEditor._editors;" +
      " const editor = editors && Object.values(editors)[0];" +
      " return editor && typeof editor.getDocumentData === 'function'" +
      " ? JSON.stringify(editor.getDocumentData()) : null; })()",
  );
  if (!raw) throw page.fail("editor_failed", "편집기 문서를 읽지 못했다");
  try {
    return JSON.parse(raw) as EditorDocument;
  } catch {
    throw page.fail("editor_failed", "편집기 문서를 읽지 못했다");
  }
}

/**
 * 임시저장 목록을 열어 그 글을 편집기에 불러온다. 빈 편집기에서는 확인 창 없이 바로 불러온다(실측).
 * 목록 API 의 차례로 글 단추를 고르고, 목록의 제목이 단추의 제목으로 시작하는지 본 뒤 누른다.
 * 목록의 제목이 비었으면 견주지 않고, 불러온 뒤의 문서 번호 대조에 맡긴다.
 * 편집기 문서의 `documentId` 가 그 글 번호가 되면 불러온 것이다. 목록에 없으면 `NAVER_BLOG_DRAFT_NOT_FOUND` 다.
 */
export async function loadDraft(page: EditorPage, blogId: string, draftId: string) {
  const drafts = await tempDrafts(page, blogId);
  const index = drafts.findIndex((draft) => draft.draft_id === draftId);
  if (index < 0) throw new ToolError("NAVER_BLOG_DRAFT_NOT_FOUND");
  const note = await blockingPopup(page);
  if (note) throw page.fail("editor_failed", "화면을 덮은 알림이 있어 글을 불러오지 못한다");
  if (!(await page.mouseClick(`document.querySelector(${q(LIST_BUTTON)})`)))
    throw page.fail("editor_failed", "임시저장 목록 단추를 찾지 못했다");
  const item = `document.querySelectorAll(${q(LIST_ITEM)})[${index}]`;
  const shown = await page.waitUntil(async () => Boolean(await page.js(`!!${item}`)), page.step(5));
  const itemTitle = shown ? ((await page.js<string>(`${item}.querySelector("strong")?.textContent || ""`)) ?? "") : "";
  const expected = normalize(drafts[index]!.title);
  // 화면은 긴 제목을 줄여 `…` 로 끝낼 수 있어 앞부분만 견준다.
  const prefix = normalize(itemTitle.replace(/…$/, ""));
  if (!shown || (expected && (!prefix || !expected.startsWith(prefix)))) {
    await page.mouseClick(`document.querySelector(${q(LIST_CLOSE)})`).catch(() => false);
    throw page.fail("editor_failed", "임시저장 목록의 차례가 목록 응답과 다르다");
  }
  if (!(await page.mouseClick(item))) throw page.fail("editor_failed", "임시저장 글 단추를 누르지 못했다");
  const loaded = await page.waitUntil(
    async () => String((await editorDocument(page).catch(() => null))?.documentId ?? "") === draftId,
    page.step(10),
  );
  if (!loaded) throw page.fail("editor_failed", "임시저장 글이 편집기에 불러와지지 않았다");
  const after = await blockingPopup(page);
  if (after) throw page.fail("editor_failed", "글을 불러온 뒤 알림이 떠 있다");
}
