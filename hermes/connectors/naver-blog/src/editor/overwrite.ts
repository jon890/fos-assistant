import { randomUUID } from "node:crypto";
import { draftChanges, type DraftContent, draftRevision } from "../changes.ts";
import {
  type DocumentDraft,
  DocumentShapeError,
  documentToDraft,
  draftToDocument,
  type EditorDocument,
} from "../document.ts";
import { ToolError } from "../errors.ts";
import { type Env, readConnection } from "../session.ts";
import { editorDocument, loadDraft, type TempDraft, tempDraftCount, tempDrafts } from "./drafts.ts";
import { EditorError } from "./editor-error.ts";
import { open } from "./open.ts";
import { clickSave, removeTags, setDocument, withTitle } from "./overwrite-page.ts";
import { ABORTED, closeTab, type EditorOptions, type EditorPage, openWriteTab } from "./page.ts";
import { readSettings, settings } from "./settings.ts";

/** 덮어쓰기 작업이 받는 인자. `revision` 과 `changes` 는 승인 카드에 보인 그대로다. */
export type OverwriteInput = {
  draft_id: string;
  revision: string;
  changes: string;
  title: string;
  category: string;
  tags: string[];
  body: string;
};

/** 단계 이름. `save_clicking` 은 원래 글의 저장 단추를 누르기 직전에 한 번 알린다. 사본의 저장은 알리지 않는다. */
export type OverwriteStage =
  | "open"
  | "load"
  | "verify"
  | "backup"
  | "apply"
  | "settings"
  | "save"
  | "save_clicking"
  | "state";

/** `state` 가 `null` 이면 저장은 확인했지만 편집기 상태를 읽지 못했다. */
export type OverwriteResult = {
  draft_id: string;
  backup_draft_id: string;
  backup_title: string;
  saved_before: number;
  saved_after: number;
  state: { revision: string } | null;
};

/** 원본 사본의 제목 머리. 사용자가 네이버 목록에서 사본을 찾는 표식이다. */
export const BACKUP_PREFIX = "[덮어쓰기 전 원본] ";
const TITLE_MAX = 100;
const SAVE_TRIES = 25;

const sameSet = (a: string[], b: string[]) => {
  const left = new Set(a);
  const right = new Set(b);
  return left.size === right.size && [...left].every((item) => right.has(item));
};

/** 편집기 문서를 글로 읽는다. 모양이 다르면 `editor_failed` 다. 문서의 글은 싣지 않는다. */
function readContent(page: EditorPage, doc: EditorDocument): DocumentDraft {
  try {
    return documentToDraft(doc);
  } catch (error) {
    if (error instanceof DocumentShapeError) throw page.fail("editor_failed", error.message);
    throw error;
  }
}

type Backup = { draftId: string; title: string; savedBefore: number };

/**
 * 원래 문서를 복제해 문서 번호를 비우고 제목만 사본 제목으로 바꿔 새 탭의 빈 편집기에 넣고 임시저장한다.
 * 목록 응답에 새 글이 하나 생기고 그 제목이 사본 제목이며, 원래 글의 제목과 고친 시각이 그대로일 때만 사본 번호를 돌려준다.
 * 원래 글이 바뀌었으면 `backup_failed` 와 `original_changed` 이고, 그때 새 사본이 보였으면 그 번호도 싣는다.
 * 사본의 저장 단추를 누른 뒤의 실패는 사본이 생겼는지 모르므로 중단 말고는 모두 `backup_failed` 다.
 * 연 탭은 성공이든 실패든 닫는다.
 */
async function backup(
  pageA: EditorPage,
  cdpUrl: string,
  blogId: string,
  draftId: string,
  original: EditorDocument,
  content: DocumentDraft,
  current: DraftContent,
  options: EditorOptions,
): Promise<Backup> {
  const before = await tempDrafts(pageA, blogId);
  const savedBefore = await tempDraftCount(pageA, blogId);
  const originalBefore = before.find((draft) => draft.draft_id === draftId);
  const known = new Set(before.map((draft) => draft.draft_id));
  const title = [...(BACKUP_PREFIX + content.title)].slice(0, TITLE_MAX).join("");
  const copy: EditorDocument = { ...withTitle(original, title), documentId: "" };

  const tab = await openWriteTab(cdpUrl, blogId, options).catch((error) => {
    if (error instanceof EditorError)
      throw new EditorError(error.code, "backup", error.message, error.extra);
    throw error;
  });
  const pageB = tab.page;
  pageB.stage = "backup";
  try {
    await open(pageB);
    await setDocument(pageB, copy);
    const doc = await editorDocument(pageB);
    const id = String(doc.documentId ?? "");
    if (id === draftId || known.has(id))
      throw pageB.fail("editor_failed", "사본 탭의 문서 번호가 기존 글을 가리킨다");
    const read = readContent(pageB, doc);
    if (read.title !== title || read.body !== content.body)
      throw pageB.fail("editor_failed", "사본 탭에 넣은 문서가 원래 글과 다르다");
    await settings(
      pageB,
      { title, category: current.category, tags: current.tags, body: content.body },
      randomUUID(),
    );
    if (!(await clickSave(pageB))) throw pageB.fail("backup_failed", "사본의 저장 단추를 찾지 못했다");

    let originalChanged = false;
    let created: TempDraft | undefined;
    try {
      const interval = pageB.times.saveSeconds / SAVE_TRIES;
      for (let i = 0; i < SAVE_TRIES; i++) {
        await pageB.sleep(interval);
        const now = await tempDrafts(pageB, blogId);
        // 원래 글이 200개 경계 밖으로 밀려나 보이지 않으면 바뀐 것으로 보지 않는다.
        const originalNow = now.find((draft) => draft.draft_id === draftId);
        originalChanged = Boolean(
          originalBefore &&
            originalNow &&
            (originalNow.title !== originalBefore.title ||
              originalNow.saved_at !== originalBefore.saved_at),
        );
        const fresh = now.filter((draft) => !known.has(draft.draft_id));
        const named = fresh.filter((draft) => draft.title === title);
        created = fresh.length === 1 && named.length === 1 ? named[0] : undefined;
        if (originalChanged || created) break;
      }
    } catch (error) {
      if (error instanceof EditorError && error.message === ABORTED) throw error;
      throw pageB.fail("backup_failed", "사본의 저장 단추를 누른 뒤 확인하지 못했다");
    }
    if (originalChanged)
      throw pageB.fail("backup_failed", "사본을 저장하는 동안 원래 글이 바뀌었다", {
        original_changed: true,
        ...(created ? { backup_draft_id: created.draft_id } : {}),
      });
    if (!created) throw pageB.fail("backup_failed", "목록에 사본이 생기지 않았다");
    return { draftId: created.draft_id, title, savedBefore };
  } finally {
    pageB.close();
    await closeTab(cdpUrl, tab.targetId).catch(() => {});
  }
}

/** 그 글의 고친 시각이 바뀌고 임시저장 수가 사본 하나만 늘었는지 본다. 확인하면 그 수를 돌려준다. */
async function confirmSaved(page: EditorPage, blogId: string, draftId: string, before: string, expected: number) {
  const interval = page.times.saveSeconds / SAVE_TRIES;
  for (let i = 0; i < SAVE_TRIES; i++) {
    await page.sleep(interval);
    const target = (await tempDrafts(page, blogId)).find((draft) => draft.draft_id === draftId);
    if (!target || target.saved_at === before) continue;
    const count = await tempDraftCount(page, blogId);
    if (count === expected) return count;
  }
  return null;
}

/**
 * 임시저장 글 하나를 불러와 고친 글로 덮어쓴다. 발행하지 않는다.
 * 지문과 바뀌는 내용이 승인한 것과 다르거나 기존 구성요소 줄이 그 글에 없으면 아무것도 바꾸지 않고 멈춘다.
 * 원래 글을 사본으로 먼저 임시저장하고 그 사본이 목록에 생긴 것을 확인한 뒤에만 원래 글을 고친다.
 * 단계가 바뀔 때마다 `onStage` 를 부르고 그것이 끝나기를 기다린다. 중단되면 `editor_failed` 와 `aborted` 로 끝난다.
 * 사본을 확인한 뒤의 실패는 모두 `extra.backup_draft_id` 를 싣는다.
 * 원래 글의 저장 단추를 누른 뒤의 실패는 저장됐는지 모르므로 모두 `save_unconfirmed` 다.
 * 성공이든 실패든 연 탭을 모두 닫는다.
 */
export async function runOverwrite(
  env: Env,
  input: OverwriteInput,
  onStage: (stage: OverwriteStage) => unknown,
  signal: AbortSignal,
  options: Omit<EditorOptions, "signal"> = {},
): Promise<OverwriteResult> {
  const { cdpUrl, blogId } = readConnection(env);
  const tabOptions = { ...options, signal };
  const { targetId, page } = await openWriteTab(cdpUrl, blogId, tabOptions);
  let backupId = "";
  try {
    const enter = async (stage: OverwriteStage) => {
      page.throwIfAborted();
      page.stage = stage;
      await onStage(stage);
      page.throwIfAborted();
    };
    await enter("open");
    await open(page);

    await enter("load");
    try {
      await loadDraft(page, blogId, input.draft_id);
    } catch (error) {
      if (error instanceof ToolError && error.code === "NAVER_BLOG_DRAFT_NOT_FOUND")
        throw page.fail("draft_not_found", "그 글이 임시저장 목록에 없다");
      throw error;
    }

    await enter("verify");
    const original = await editorDocument(page);
    const content = readContent(page, original);
    const { category, tags } = await readSettings(page);
    const current = { title: content.title, category, tags, body: content.body };
    const edited = { title: input.title, category: input.category, tags: input.tags, body: input.body };
    if (draftRevision(current) !== input.revision)
      throw page.fail("draft_changed", "읽은 뒤에 그 글이 바뀌었다");
    if (draftChanges(current, edited) !== input.changes)
      throw page.fail("changes_mismatch", "승인한 바뀌는 내용이 지금 글과 다르다");
    let built: EditorDocument;
    try {
      built = draftToDocument(original, input.title, input.body);
    } catch (error) {
      if (error instanceof DocumentShapeError)
        throw page.fail("component_not_found", "본문의 기존 구성요소 줄이 그 글에 없다");
      throw error;
    }

    await enter("backup");
    const copy = await backup(page, cdpUrl, blogId, input.draft_id, original, content, current, tabOptions);
    backupId = copy.draftId;

    await enter("apply");
    const expected = documentToDraft(built);
    await setDocument(page, built);
    const applied = await editorDocument(page);
    const appliedContent = readContent(page, applied);
    if (
      String(applied.documentId ?? "") !== input.draft_id ||
      appliedContent.title !== expected.title ||
      appliedContent.body !== expected.body
    )
      throw page.fail("editor_failed", "편집기에 넣은 문서가 만든 문서와 다르다");

    await enter("settings");
    await removeTags(page, tags.filter((tag) => !input.tags.includes(tag)));
    await settings(page, { ...edited, tags: input.tags.filter((tag) => !tags.includes(tag)) }, randomUUID());

    await enter("save");
    const ready = await editorDocument(page);
    const readyContent = readContent(page, ready);
    const readySettings = await readSettings(page);
    if (
      String(ready.documentId ?? "") !== input.draft_id ||
      readyContent.title !== expected.title ||
      readyContent.body !== expected.body ||
      readySettings.category !== input.category ||
      !sameSet(readySettings.tags, input.tags)
    )
      throw page.fail("editor_failed", "저장하기 전 편집기가 고친 글과 다르다");
    const targetBefore = (await tempDrafts(page, blogId)).find((draft) => draft.draft_id === input.draft_id);
    if (!targetBefore?.saved_at)
      throw page.fail("editor_failed", "목록에서 그 글의 고친 시각을 읽지 못해 저장하지 않는다");

    await onStage("save_clicking");
    let savedAfter: number | null;
    try {
      // JS 의 `.click()` 은 저장되지 않는다. 사람이 누른 것으로 인정되는 마우스 이벤트를 쓴다.
      if (!(await clickSave(page))) throw page.fail("save_unconfirmed", "저장 단추를 누르지 못했다");
      savedAfter = await confirmSaved(page, blogId, input.draft_id, targetBefore.saved_at, copy.savedBefore + 1);
    } catch (error) {
      throw page.fail("save_unconfirmed", `저장 단추를 누른 뒤 확인하지 못했다: ${(error as Error).message}`);
    }
    if (savedAfter === null)
      throw page.fail("save_unconfirmed", "저장 단추는 눌렀지만 그 글이 고쳐진 것을 목록에서 확인하지 못했다");

    let state: OverwriteResult["state"] = null;
    try {
      await enter("state");
      const doc = readContent(page, await editorDocument(page));
      const now = await readSettings(page);
      state = {
        revision: draftRevision({ title: doc.title, category: now.category, tags: now.tags, body: doc.body }),
      };
    } catch {
      state = null;
    }
    return {
      draft_id: input.draft_id,
      backup_draft_id: copy.draftId,
      backup_title: copy.title,
      saved_before: copy.savedBefore + 1,
      saved_after: savedAfter,
      state,
    };
  } catch (error) {
    if (!backupId) throw error;
    // 사본을 확인한 뒤의 실패는 사용자가 사본을 찾을 수 있게 사본 번호를 싣는다.
    if (error instanceof EditorError)
      throw new EditorError(error.code, error.stage, error.message, {
        ...error.extra,
        backup_draft_id: backupId,
      });
    throw new EditorError("editor_failed", page.stage, "사본을 만든 뒤 멈췄다", {
      backup_draft_id: backupId,
    });
  } finally {
    page.close();
    await closeTab(cdpUrl, targetId).catch(() => {});
  }
}
