import type { Block, DraftInput } from "../draft.ts";
import { componentProblems, componentState } from "./components.ts";
import {
  BODY_SELECTOR,
  buttonFinder,
  click,
  componentCount,
  type EditorPage,
  normalize,
  paragraphs,
  progress,
  STAGES,
  TITLE_SELECTOR,
} from "./page.ts";
import { FITTED_COUNT, imageCount, incompleteImages } from "./photos.ts";
import {
  closeSettings,
  draftTags,
  openSettings,
  readSettings,
  SAVE_BUTTON,
  SAVE_TRIES,
  settingsOpen,
  settingsState,
} from "./settings.ts";

/** 임시저장된 글 수를 저장 단추 옆 단추의 aria-label 에서 읽는 식. */
const SAVE_COUNT = `(() => {
  const b = document.querySelector('[aria-label*="임시저장된 글 보기"]');
  if (!b) return null;
  const m = (b.getAttribute("aria-label") || b.innerText).match(/(\\d+)/);
  return m ? Number(m[1]) : null;
})()`;

const saveCount = (page: EditorPage) => page.js<number | null>(SAVE_COUNT);

/** 앞 단계의 성공과 실제 화면이 같은 초안을 가리키는지 본다. 어긋난 자리를 문장으로 돌려준다. */
async function saveReadiness(
  page: EditorPage,
  input: DraftInput,
  blocks: Block[],
  draftHash: string,
) {
  const current = await progress(page);
  if (current.draftHash !== draftHash)
    return ["이 탭에서 검사한 초안과 저장할 초안이 다르다"];
  const missing = STAGES.filter(
    (stage) => !(current.passed ?? []).includes(stage),
  );
  if (missing.length) return [`끝나지 않은 단계: ${missing.join(", ")}`];

  const problems: string[] = [];
  const title = normalize((await paragraphs(page, TITLE_SELECTOR)).join(""));
  if (title !== normalize(input.title)) problems.push("제목이 초안과 다르다");
  const body = (await paragraphs(page, BODY_SELECTOR))
    .map(normalize)
    .filter(Boolean);
  const wantedText = blocks
    .flatMap((block) => (block.type === "text" ? [normalize(block.line)] : []))
    .filter(Boolean);
  let cursor = 0;
  for (const line of body)
    if (cursor < wantedText.length && line === wantedText[cursor]) cursor++;
  if (cursor !== wantedText.length)
    problems.push(`본문 글이 빠졌다: ${cursor}/${wantedText.length}줄 확인`);
  if (body.some((line) => /^\[(사진|스티커|장소) 자리:/.test(line)))
    problems.push("사진, 스티커, 장소 자리표시 글이 남았다");

  const expected = {
    사진: blocks.filter((block) => block.type === "image").length,
    스티커: blocks.filter((block) => block.type === "sticker").length,
    지도: blocks.filter((block) => block.type === "map").length,
  };
  const actual = {
    사진: await imageCount(page),
    스티커: await componentCount(page, "sticker"),
    지도: await componentCount(page, "placesMap"),
  };
  for (const name of Object.keys(expected) as Array<keyof typeof expected>)
    if (actual[name] !== expected[name])
      problems.push(
        `${name} 수가 다르다: 초안 ${expected[name]}, 화면 ${actual[name]}`,
      );
  problems.push(...componentProblems(blocks, await componentState(page), body));
  const fit = (await page.js<number>(FITTED_COUNT)) || 0;
  if (fit !== expected.사진)
    problems.push(
      `문서 너비 사진 수가 다르다: 초안 ${expected.사진}, 화면 ${fit}`,
    );

  if (!(await openSettings(page)))
    problems.push("카테고리와 태그를 읽을 수 없다");
  else {
    const state = await settingsState(page);
    if (state.category !== input.category.trim())
      problems.push("카테고리가 초안과 다르다");
    const tags = draftTags(input);
    if (
      state.tags.length !== new Set(tags).size ||
      tags.some((tag) => !state.tags.includes(tag))
    )
      problems.push("태그가 초안과 다르다");
    if (!(await closeSettings(page))) problems.push("발행 설정을 닫지 못했다");
  }
  if (actual.사진 === expected.사진) {
    const incomplete = await incompleteImages(page, actual.사진);
    if (incomplete.length)
      problems.push(
        `사진 전송이 끝나지 않았다: ${incomplete.map((i) => i + 1).join(", ")}번째`,
      );
  }
  return problems;
}

/**
 * 초안과 화면을 대조한 뒤 임시저장 단추만 누르고 저장 수가 늘었는지 본다.
 * 누르기 직전에 `onStage("save_clicking")` 을 부르고 그것이 끝난 뒤에 누른다.
 * 그 뒤에 난 실패는 저장됐는지 모르므로 모두 `save_unconfirmed` 다.
 * 알린 뒤 마우스 이벤트를 보내기 전에 중단 신호가 와 누르지 않았어도 작업 상태는 이미 `save_clicked` 라 `unknown` 이 된다. 다시 저장하지 않게 하는 보수적 처리다.
 */
export async function save(
  page: EditorPage,
  input: DraftInput,
  blocks: Block[],
  draftHash: string,
  onStage: (stage: string) => unknown,
) {
  if (await settingsOpen(page))
    throw page.fail(
      "editor_failed",
      "발행 설정을 먼저 닫아야 임시저장할 수 있다",
    );
  const problems = await saveReadiness(page, input, blocks, draftHash);
  if (problems.length)
    throw page.fail(
      "editor_failed",
      `임시저장을 거절했다: ${problems.join("; ")}`,
    );
  const savedBefore = await saveCount(page);
  if (typeof savedBefore !== "number")
    throw page.fail("editor_failed", "임시저장 수를 읽지 못해 저장하지 않는다");
  const photoCount = blocks.filter((block) => block.type === "image").length;
  if (
    (await imageCount(page)) !== photoCount ||
    (await incompleteImages(page, photoCount)).length
  )
    throw page.fail(
      "editor_failed",
      "저장 직전에 사진 수나 전송 상태가 달라져 저장하지 않는다",
    );
  const finder = buttonFinder("button", SAVE_BUTTON);
  if (!(await page.js(`!!(${finder})`)))
    throw page.fail("editor_failed", "저장 단추를 찾지 못했다");

  await onStage("save_clicking");
  try {
    // JS 의 `.click()` 은 저장되지 않는다. 사람이 누른 것으로 인정되는 마우스 이벤트를 쓴다.
    if (!(await page.mouseClick(finder)))
      throw page.fail("save_unconfirmed", "저장 단추를 누르지 못했다");
    const interval = page.times.saveSeconds / SAVE_TRIES;
    for (let i = 0; i < SAVE_TRIES; i++) {
      await page.sleep(interval);
      const savedAfter = await saveCount(page);
      if (typeof savedAfter === "number" && savedAfter > savedBefore)
        return { savedBefore, savedAfter };
    }
  } catch (error) {
    throw page.fail(
      "save_unconfirmed",
      `저장 단추를 누른 뒤 확인하지 못했다: ${(error as Error).message}`,
    );
  }
  throw page.fail(
    "save_unconfirmed",
    "저장 단추는 눌렀지만 임시저장 수가 늘지 않았다",
  );
}

export type EditorState = {
  title: string;
  photos: number;
  fitted_photos: number;
  stickers: number;
  maps: number;
  category: string;
  tags: string[];
  saved_count: number | null;
};

/** 편집기에 실제로 들어간 것을 읽는다. */
export async function state(page: EditorPage): Promise<EditorState> {
  const current = await readSettings(page);
  return {
    title: normalize((await paragraphs(page, TITLE_SELECTOR)).join("")),
    photos: await imageCount(page),
    fitted_photos: (await page.js<number>(FITTED_COUNT)) || 0,
    stickers: await componentCount(page, "sticker"),
    maps: await componentCount(page, "placesMap"),
    category: current.category,
    tags: current.tags,
    saved_count: await saveCount(page),
  };
}
