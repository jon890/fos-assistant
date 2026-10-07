import { createHash } from "node:crypto";
import { ATTACHMENT_DIR_ENV, type Block, type DraftInput } from "../draft.ts";
import { type Env, readConnection } from "../session.ts";
import { components } from "./components.ts";
import { open } from "./open.ts";
import { closeTab, type EditorOptions, openWriteTab } from "./page.ts";
import { photos } from "./photos.ts";
import { type EditorState, save, settings, state } from "./settings.ts";
import { fill } from "./text.ts";

export { EditorError, type EditorErrorCode } from "./page.ts";
export type { EditorState } from "./settings.ts";

/** 단계 이름. `save_clicking` 은 저장 단추를 누르기 직전에 한 번 알린다. */
export type RunStage =
  | "open"
  | "fill"
  | "photos"
  | "components"
  | "settings"
  | "save"
  | "save_clicking"
  | "state";

/** `state` 가 `null` 이면 임시저장은 확인했지만 편집기 상태를 읽지 못했다. */
export type RunResult = { state: EditorState | null; savedBefore: number; savedAfter: number };

/** 탭의 진행 표시가 같은 초안을 가리키는지 보려고 쓰는 초안의 해시. */
function draftHash(input: DraftInput) {
  return createHash("sha256")
    .update(JSON.stringify([input.title, input.category, input.tags, input.body]))
    .digest("hex");
}

/**
 * 새 탭에 글쓰기 화면을 열어 초안을 넣고 임시저장한다. 발행하지 않는다.
 * 단계가 바뀔 때마다 `onStage` 를 부르고 그것이 끝나기를 기다린다.
 * 단계 사이와 기다리는 동안 `signal` 을 보고, 중단되면 `editor_failed` 와 `aborted` 로 끝난다.
 * 저장 수가 늘어난 것을 확인한 뒤의 `state` 단계 실패는 삼키고 `state: null` 로 성공한다. 임시저장은 이미 됐다.
 * 성공이든 실패든 연 탭 하나를 닫는다.
 */
export async function runDraft(
  env: Env,
  blocks: Block[],
  input: DraftInput,
  onStage: (stage: RunStage) => unknown,
  signal: AbortSignal,
  options: Omit<EditorOptions, "signal"> = {},
): Promise<RunResult> {
  const { cdpUrl, blogId } = readConnection(env);
  const hash = draftHash(input);
  const { targetId, page } = await openWriteTab(cdpUrl, blogId, { ...options, signal });
  try {
    const enter = async (stage: RunStage) => {
      page.throwIfAborted();
      page.stage = stage;
      await onStage(stage);
      page.throwIfAborted();
    };
    await enter("open");
    await open(page);
    await enter("fill");
    await fill(page, input.title, blocks, hash);
    await enter("photos");
    await photos(page, input, blocks, hash, env[ATTACHMENT_DIR_ENV]);
    await enter("components");
    await components(page, blocks, hash);
    await enter("settings");
    await settings(page, input, hash);
    await enter("save");
    const saved = await save(page, input, blocks, hash, onStage as (stage: string) => unknown);
    try {
      await enter("state");
      return { state: await state(page), ...saved };
    } catch {
      return { state: null, ...saved };
    }
  } finally {
    page.close();
    await closeTab(cdpUrl, targetId).catch(() => {});
  }
}
