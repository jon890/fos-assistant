import { draftRevision } from "./changes.ts";
import { DocumentShapeError, documentToDraft } from "./document.ts";
import { editorDocument, loadDraft, tempDrafts } from "./editor/drafts.ts";
import { open } from "./editor/open.ts";
import { closeTab, EditorError, type EditorOptions, type EditorPage, openWriteTab } from "./editor/page.ts";
import { readSettings } from "./editor/settings.ts";
import { ToolError } from "./errors.ts";
import { assertIdle, openJobDir } from "./jobs.ts";
import { readConnection, type Env } from "./session.ts";

/** 읽기 도구 하나의 시간 상한. Hermes 의 도구 호출 제한(300초)보다 짧다. */
const READ_LIMIT_MS = 120_000;

export type ReadOptions = Omit<EditorOptions, "signal"> & { limitMs?: number };

/** 편집기 단계의 실패를 도구 오류로 바꾼다. 화면에서 읽은 글과 문장은 싣지 않는다. */
function toolErrorOf(error: unknown): ToolError {
  if (error instanceof ToolError) return error;
  if (error instanceof EditorError) {
    if (error.code === "login_required") return new ToolError("NAVER_BLOG_LOGIN_REQUIRED");
    if (error.code === "draft_not_found") return new ToolError("NAVER_BLOG_DRAFT_NOT_FOUND");
  }
  return new ToolError("NAVER_BLOG_UNAVAILABLE");
}

/**
 * 그 블로그의 글쓰기 화면을 새 탭에 열고 `work` 를 돌린 뒤 탭을 닫는다.
 * 같은 블로그에 돌고 있는 저장 작업이 있으면 탭을 열지 않고 `NAVER_BLOG_BUSY` 다.
 * 읽기는 잠금을 잡지 않는다. 읽는 동안 시작한 저장 작업은 자기 탭의 화면을 따로 대조한다.
 */
async function withWriteTab<T>(
  env: Env,
  options: ReadOptions,
  work: (page: EditorPage, blogId: string) => Promise<T>,
): Promise<T> {
  const { cdpUrl, blogId } = readConnection(env);
  await assertIdle(await openJobDir(env), blogId);
  const { limitMs = READ_LIMIT_MS, ...times } = options;
  const controller = new AbortController();
  const limit = setTimeout(() => controller.abort(), limitMs);
  let tab: Awaited<ReturnType<typeof openWriteTab>> | null = null;
  try {
    tab = await openWriteTab(cdpUrl, blogId, { ...times, signal: controller.signal });
    await open(tab.page);
    return await work(tab.page, blogId);
  } catch (error) {
    throw toolErrorOf(error);
  } finally {
    clearTimeout(limit);
    if (tab) {
      tab.page.close();
      await closeTab(cdpUrl, tab.targetId).catch(() => {});
    }
  }
}

/** 임시저장 글의 번호와 제목, 고친 시각을 목록 차례대로 돌려준다. 편집기에 글을 불러오지 않는다. */
export function listDrafts(env: Env, options: ReadOptions = {}) {
  return withWriteTab(env, options, async (page, blogId) => {
    const drafts = await tempDrafts(page, blogId);
    return { count: drafts.length, drafts };
  });
}

/**
 * 임시저장 글 하나를 편집기에 불러와 제목, 카테고리, 태그, 본문을 읽는다. 저장 단추는 누르지 않는다.
 * 본문은 한 줄이 한 문단이고, 글이 아닌 구성요소는 `[기존 사진 1]` 같은 줄이다.
 * `revision` 은 그 네 칸의 지문이다. 덮어쓰기는 불러온 글로 다시 계산해 이 값과 대조한다.
 */
export function readDraft(env: Env, draftId: string, options: ReadOptions = {}) {
  return withWriteTab(env, options, async (page, blogId) => {
    await loadDraft(page, blogId, draftId);
    let content;
    try {
      content = documentToDraft(await editorDocument(page));
    } catch (error) {
      if (error instanceof DocumentShapeError) throw page.fail("editor_failed", error.message);
      throw error;
    }
    const { category, tags } = await readSettings(page);
    const draft = { title: content.title, category, tags, body: content.body };
    return { draft_id: draftId, revision: draftRevision(draft), ...draft };
  });
}
