import type { EditorDocument } from "../document.ts";
import { ToolError } from "../errors.ts";
import { probe } from "./open.ts";
import { blockingPopup, type EditorPage, normalize } from "./page.ts";

/** 임시저장 목록 단추. 이 단추의 aria-label 에 임시저장 수가 있다. */
const LIST_BUTTON = '[aria-label*="임시저장된 글 보기"]';
/** 열린 임시저장 목록의 글 단추. 목록 API 와 같은 차례로 놓인다(실측). */
const LIST_ITEM = 'button[data-click-area="tpb*s.tlist"]';
const LIST_CLOSE = 'button[data-click-area="tpb*s.close"]';
const LIST_MAX = 200;
export const DRAFT_ID_PATTERN = /^[1-9][0-9]{0,19}$/;

const q = (value: unknown) => JSON.stringify(value);

export type TempDraft = {
  draft_id: string;
  title: string;
  saved_at: string | null;
};

/**
 * 편집기가 임시저장 목록을 열 때 부르는 목록 API 를 같은 탭에서 부른다(실측).
 * 응답은 앞에 줄바꿈이 붙은 JSON 이고 `result.tempPostList` 와 `result.totalCount` 를 돌려준다.
 * 모양이 다르면 `editor_failed` 다. 응답의 글은 싣지 않는다.
 */
async function tempPostList(page: EditorPage, blogId: string) {
  const raw = await page.js<string>(`(async () => {
  const response = await fetch(${q(`/TempPostList.naver?blogId=${encodeURIComponent(blogId)}&editorVersion=4&onlyCount=false`)}, {credentials: "include"});
  return JSON.stringify({status: response.status, text: await response.text()});
})()`);
  let list: unknown;
  let totalCount: unknown;
  try {
    const { status, text } = JSON.parse(raw || "{}") as {
      status?: number;
      text?: string;
    };
    const body = JSON.parse((text ?? "").trim()) as {
      isSuccess?: unknown;
      result?: { tempPostList?: unknown; totalCount?: unknown };
    };
    if (status !== 200 || body.isSuccess !== true)
      throw new Error("목록 응답이 실패다");
    list = body.result?.tempPostList;
    totalCount = body.result?.totalCount;
  } catch {
    throw page.fail("editor_failed", "임시저장 목록 응답을 읽지 못했다");
  }
  if (!Array.isArray(list))
    throw page.fail("editor_failed", "임시저장 목록 응답의 모양이 다르다");
  return { list: list as unknown[], totalCount };
}

/** 임시저장 목록의 앞 200개 글을 `logNo`, `title`, `modiDate` 로 읽는다. */
export async function tempDrafts(
  page: EditorPage,
  blogId: string,
): Promise<TempDraft[]> {
  return draftsOf(page, (await tempPostList(page, blogId)).list);
}

/** 목록 응답의 글을 검사해 앞 200개를 옮긴다. 모양이 다르면 `editor_failed` 다. */
function draftsOf(page: EditorPage, list: unknown[]): TempDraft[] {
  return list.slice(0, LIST_MAX).map((item) => {
    const { logNo, title, modiDate } = (item ?? {}) as Record<string, unknown>;
    const id =
      typeof logNo === "number" && Number.isSafeInteger(logNo)
        ? String(logNo)
        : "";
    if (!DRAFT_ID_PATTERN.test(id) || typeof title !== "string")
      throw page.fail("editor_failed", "임시저장 목록 응답의 모양이 다르다");
    const saved =
      typeof modiDate === "number" && Number.isFinite(modiDate)
        ? new Date(modiDate)
        : null;
    return {
      draft_id: id,
      title,
      saved_at:
        saved && !Number.isNaN(saved.getTime()) ? saved.toISOString() : null,
    };
  });
}

/**
 * 임시저장 글 수. 목록은 200개에서 잘리므로 응답의 `totalCount` 를 쓰고, 그 칸이 수가 아니면 목록 길이를 센다.
 * 저장 단추 옆의 수는 그 탭이 저장할 때만 새로 읽혀 다른 탭의 저장을 반영하지 않아 쓰지 않는다.
 */
export async function tempDraftCount(
  page: EditorPage,
  blogId: string,
): Promise<number> {
  const { list, totalCount } = await tempPostList(page, blogId);
  return countOf(list, totalCount);
}

function countOf(list: unknown[], totalCount: unknown) {
  return typeof totalCount === "number" &&
    Number.isSafeInteger(totalCount) &&
    totalCount >= 0
    ? totalCount
    : list.length;
}

export type TempList = { drafts: TempDraft[]; count: number };

/** 목록 응답 한 번으로 앞 200개 글과 전체 수를 함께 읽는다. 읽기 전후를 같은 시점의 값으로 견줄 때 쓴다. */
export async function tempList(
  page: EditorPage,
  blogId: string,
): Promise<TempList> {
  const { list, totalCount } = await tempPostList(page, blogId);
  return { drafts: draftsOf(page, list), count: countOf(list, totalCount) };
}

/** 편집기의 문서를 읽는다. 편집기 객체나 문서가 없으면 `editor_failed` 다. */
export async function editorDocument(
  page: EditorPage,
): Promise<EditorDocument> {
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

/** 새 편집기에 놓일 수 있는 구성요소 종류. */
const EMPTY_KINDS = new Set(["documentTitle", "text"]);
/** 이미 읽기에서 다루는 비글 구성요소. 모르는 종류는 작성 중인 글로 진단하지 않는다. */
const CONTENT_KINDS = new Set(["image", "sticker", "placesMap"]);

/** 문단 배열에 공백 밖의 글자가 있는지 본다. 모르는 모양은 빈 글로 간주하지 않는다. */
function hasText(page: EditorPage, paragraphs: unknown) {
  if (!Array.isArray(paragraphs))
    throw page.fail("editor_failed", "편집기 문단의 모양이 다르다");
  return paragraphs.some((paragraph) => {
    const nodes = (paragraph as { nodes?: unknown } | null)?.nodes;
    if (!Array.isArray(nodes))
      throw page.fail("editor_failed", "편집기 문단의 모양이 다르다");
    return nodes.some((node) => {
      const value = (node as { value?: unknown } | null)?.value;
      if (typeof value !== "string")
        throw page.fail("editor_failed", "편집기 글 조각의 모양이 다르다");
      return /\S/.test(value);
    });
  });
}

/**
 * 글을 불러오기 전에 새 탭의 편집기가 비었는지 본다. 읽기 도구가 사용자가 쓰던 글 위에 다른 글을 싣지 않게 한다.
 * 편집기 객체를 읽지 못하면 누르기 전에 `editor_failed` 다. 불러온 뒤에야 모양이 바뀐 것을 알면 그 사이 자동저장이 돌 수 있다.
 * 제목이나 글 문단에 글자가 있거나, 사진처럼 글이 아닌 구성요소가 있거나, 문서 번호가 목록에 있는 글 번호면 「작성 중인 글」 알림이 없어도 `NAVER_BLOG_EDITOR_IN_USE` 다.
 */
export async function assertEmptyEditor(page: EditorPage, listed: TempDraft[]) {
  const doc = await editorDocument(page);
  const components = doc.document?.components;
  if (!Array.isArray(components))
    throw page.fail("editor_failed", "편집기 구성요소의 모양이 다르다");
  for (const component of components) {
    const kind = String(component?.["@ctype"] ?? "");
    if (!EMPTY_KINDS.has(kind) && !CONTENT_KINDS.has(kind))
      throw page.fail("editor_failed", "편집기 구성요소의 종류를 알지 못한다");
  }
  const written = components.some(
    (component) =>
      !EMPTY_KINDS.has(String(component?.["@ctype"] ?? "")) ||
      hasText(
        page,
        component?.["@ctype"] === "documentTitle"
          ? component.title
          : component.value,
      ),
  );
  if (
    written ||
    listed.some((draft) => draft.draft_id === String(doc.documentId ?? ""))
  )
    throw new ToolError("NAVER_BLOG_EDITOR_IN_USE");
}

/** 로그인 화면으로 넘어간 탭이면 `login_required` 로 멈춘다. 글을 불러오지 못한 까닭을 가를 때 부른다. */
async function failIfLoginPage(page: EditorPage) {
  const diagnostics = await probe(page, 1);
  if (diagnostics.last.reason === "login_required")
    throw page.fail("login_required", "로그인 화면으로 넘어갔다");
}

/**
 * 임시저장 목록을 열어 그 글을 편집기에 불러온다. 빈 편집기에서는 확인 창 없이 바로 불러온다(실측).
 * 목록 API 의 차례로 글 단추를 고르고, 목록의 제목이 단추의 제목으로 시작하는지 본 뒤 누른다.
 * 목록의 제목이 비었으면 견주지 않고, 불러온 뒤의 문서 번호 대조에 맡긴다.
 * 편집기 문서의 `documentId` 가 그 글 번호가 되면 불러온 것이다. 목록에 없으면 `NAVER_BLOG_DRAFT_NOT_FOUND` 다.
 * 이미 읽은 목록을 `listed` 로 주면 목록 응답을 다시 부르지 않는다.
 */
export async function loadDraft(
  page: EditorPage,
  blogId: string,
  draftId: string,
  listed?: TempDraft[],
) {
  const drafts = listed ?? (await tempDrafts(page, blogId));
  const index = drafts.findIndex((draft) => draft.draft_id === draftId);
  if (index < 0) throw new ToolError("NAVER_BLOG_DRAFT_NOT_FOUND");
  const note = await blockingPopup(page);
  if (note)
    throw page.fail(
      "editor_failed",
      "화면을 덮은 알림이 있어 글을 불러오지 못한다",
    );
  if (!(await page.mouseClick(`document.querySelector(${q(LIST_BUTTON)})`)))
    throw page.fail("editor_failed", "임시저장 목록 단추를 찾지 못했다");
  const item = `document.querySelectorAll(${q(LIST_ITEM)})[${index}]`;
  const shown = await page.waitUntil(
    async () => Boolean(await page.js(`!!${item}`)),
    page.step(5),
  );
  const itemTitle = shown
    ? ((await page.js<string>(
        `${item}.querySelector("strong")?.textContent || ""`,
      )) ?? "")
    : "";
  const expected = normalize(drafts[index]!.title);
  // 화면은 긴 제목을 줄여 `…` 로 끝낼 수 있어 앞부분만 견준다.
  const prefix = normalize(itemTitle.replace(/…$/, ""));
  if (!shown || (expected && (!prefix || !expected.startsWith(prefix)))) {
    await page
      .mouseClick(`document.querySelector(${q(LIST_CLOSE)})`)
      .catch(() => false);
    throw page.fail(
      "editor_failed",
      "임시저장 목록의 차례가 목록 응답과 다르다",
    );
  }
  if (!(await page.mouseClick(item)))
    throw page.fail("editor_failed", "임시저장 글 단추를 누르지 못했다");
  const loaded = await page.waitUntil(
    async () =>
      String(
        (await editorDocument(page).catch(() => null))?.documentId ?? "",
      ) === draftId,
    page.step(10),
  );
  if (!loaded) {
    await failIfLoginPage(page);
    throw page.fail(
      "editor_failed",
      "임시저장 글이 편집기에 불러와지지 않았다",
    );
  }
  const after = await blockingPopup(page);
  if (after) throw page.fail("editor_failed", "글을 불러온 뒤 알림이 떠 있다");
}
