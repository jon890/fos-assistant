import type { Block } from "../draft.ts";
import type { FocusDiagnostics } from "./focus.ts";
import {
  BODY_SELECTOR,
  clearField,
  type EditorPage,
  focusField,
  newParagraph,
  normalize,
  paragraphs,
  requireClearScreen,
  setStage,
  TITLE_SELECTOR,
  typeLine,
} from "./page.ts";

type ImageBlock = Extract<Block, { type: "image" }>;
type StickerBlock = Extract<Block, { type: "sticker" }>;
type MapBlock = Extract<Block, { type: "map" }>;

/** 사진 자리에 먼저 넣어 두는 글. 사진 단계가 이 문단을 지우고 그 자리에 사진을 넣는다. */
export function imagePlaceholder(block: ImageBlock) {
  return `[사진 자리: ${block.file}]`;
}

/** 실제 스티커로 바꿀 자리의 글. */
export function stickerPlaceholder(block: StickerBlock) {
  return `[스티커 자리: ${block.code}]`;
}

/** 실제 지도 카드로 바꿀 자리의 글. */
export function mapPlaceholder(block: MapBlock) {
  return `[장소 자리: ${block.name} | ${block.address}]`;
}

/** 본문 블록을 편집기에 넣을 줄 목록으로 편다. 한 줄이 한 문단이고 끝의 빈 줄은 뺀다. */
export function bodyLines(blocks: Block[]) {
  const lines = blocks.map((block) => {
    switch (block.type) {
      case "text":
        return block.line;
      case "image":
        return imagePlaceholder(block);
      case "sticker":
        return stickerPlaceholder(block);
      case "map":
        return mapPlaceholder(block);
    }
  });
  while (lines.length && !lines[lines.length - 1]) lines.pop();
  return lines;
}

/** 초안 줄과 화면 문단을 견줘 처음 어긋난 자리를 돌려준다. 초안의 글은 싣지 않는다. */
function bodyMismatch(want: string[], got: string[]) {
  for (let i = 0; i < Math.min(want.length, got.length); i++)
    if (want[i] !== got[i]) return `${i + 1}번째 줄이 초안과 다르다`;
  if (want.length !== got.length)
    return `줄 수가 다르다. 초안 ${want.length}줄, 화면 ${got.length}줄`;
  return "";
}

/**
 * 커서를 두지 못한 까닭의 고정 사유 코드. 마지막 시도에서 클릭 지점이 그 자리 밖 요소를 맞았으면 덮였다고 본다.
 * Control Plane 이 이 코드만 보관하므로 화면의 글과 요소 이름은 담지 않는다.
 */
function cursorReason(area: "title" | "body", diagnostics: FocusDiagnostics) {
  const last = diagnostics.samples.at(-1);
  return `${area}_cursor_${last?.hit === "other" ? "covered" : "failed"}`;
}

/** 제목과 본문 글자를 넣고, 구성요소 자리에는 자리표시 글을 둔다. 넣은 뒤 화면과 초안을 대조한다. */
export async function fill(
  page: EditorPage,
  title: string,
  blocks: Block[],
  draftHash: string,
) {
  await setStage(page, draftHash, "fill", false);
  const note = await requireClearScreen(page);
  if (note)
    throw page.fail(
      "editor_failed",
      "화면을 덮은 알림이 있어 글자를 넣지 못한다",
      {
        reason: "screen_blocked",
      },
    );

  const titleFocus = await focusField(
    page,
    TITLE_SELECTOR,
    ".se-documentTitle",
  );
  if (!titleFocus.focused)
    throw page.fail("editor_failed", "제목 자리에 커서를 두지 못했다", {
      focus_diagnostics: titleFocus.diagnostics,
      reason: cursorReason("title", titleFocus.diagnostics),
    });
  await clearField(page);
  await typeLine(page, TITLE_SELECTOR, title);

  const bodyFocus = await focusField(
    page,
    BODY_SELECTOR,
    ".se-component.se-text",
  );
  if (!bodyFocus.focused)
    throw page.fail("editor_failed", "본문 자리에 커서를 두지 못했다", {
      focus_diagnostics: bodyFocus.diagnostics,
      reason: cursorReason("body", bodyFocus.diagnostics),
    });
  await clearField(page);
  const lines = bodyLines(blocks);
  for (const [index, line] of lines.entries()) {
    if (line) await typeLine(page, BODY_SELECTOR, line);
    if (index !== lines.length - 1) await newParagraph(page, BODY_SELECTOR);
  }

  const landed = normalize((await paragraphs(page, TITLE_SELECTOR)).join(""));
  if (landed !== normalize(title))
    throw page.fail("editor_failed", "제목이 들어가지 않았다", {
      reason: "title_mismatch",
    });

  const want = lines.map(normalize).filter(Boolean);
  const got = (await paragraphs(page, BODY_SELECTOR))
    .map(normalize)
    .filter(Boolean);
  const wrong = bodyMismatch(want, got);
  if (wrong)
    throw page.fail("editor_failed", `본문이 초안과 다르다. ${wrong}`, {
      reason: "body_mismatch",
    });
  await setStage(page, draftHash, "fill", true);
}
