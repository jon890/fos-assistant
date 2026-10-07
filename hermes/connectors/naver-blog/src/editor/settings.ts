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
  requireClearScreen,
  SETTLE_SECONDS,
  setStage,
  STAGES,
  TITLE_SELECTOR,
} from "./page.ts";
import { FITTED_COUNT, imageCount, incompleteImages } from "./photos.ts";

// 상단의 발행 단추는 발행 설정 레이어를 열고 닫기만 한다.
// 설정 안의 발행 확인 단추 `tpb*i.publish` 는 누르지 않는다.
const PUBLISH_SETTINGS_BUTTON = 'button[data-click-area="tpb.publish"]';
const CATEGORY_BUTTON = 'button[data-click-area="tpb*i.category"]';
const TAG_INPUT = "#tag-input";
const SAVE_BUTTON = "저장";
const CATEGORIES_MAX = 50;
const SAVE_TRIES = 25;

const q = (value: unknown) => JSON.stringify(value);

/** 초안의 태그를 편집기 칩과 같은 모양으로 맞춘다. */
const draftTags = (input: DraftInput) =>
  input.tags.map((tag) => tag.replace(/^#+/, "").trim()).filter(Boolean);

/** 접힌 설정도 화면을 가리므로 발행 설정 레이어가 있는지를 읽는다. */
async function settingsOpen(page: EditorPage) {
  return Boolean(await page.js("!!document.querySelector('[class^=layer_publish]')"));
}

/** 발행 설정 레이어만 연다. */
async function openSettings(page: EditorPage) {
  if (await settingsOpen(page)) return true;
  if (!(await click(page, PUBLISH_SETTINGS_BUTTON))) return false;
  return page.waitUntil(() => settingsOpen(page));
}

/** 연 단추를 다시 눌러 설정 레이어만 닫는다. */
async function closeSettings(page: EditorPage) {
  if (!(await settingsOpen(page))) return true;
  if (!(await click(page, PUBLISH_SETTINGS_BUTTON))) return false;
  return page.waitUntil(async () => !(await settingsOpen(page)));
}

/** 열린 발행 설정의 카테고리와 태그를 읽는다. */
async function settingsState(page: EditorPage) {
  const raw = await page.js<string>(
    "JSON.stringify({" +
      ` category: document.querySelector(${q(CATEGORY_BUTTON)})?.textContent.trim() || '',` +
      " tags: [...document.querySelectorAll('span[id^=\"tag-item-\"][aria-label]')]" +
      "   .map(e => e.getAttribute('aria-label'))" +
      "})",
  );
  const state = JSON.parse(raw || "{}") as { category?: string; tags?: string[] };
  return { category: state.category ?? "", tags: [...new Set(state.tags ?? [])] };
}

/** 목록의 카테고리 이름만 대조해 보이는 label 을 찾는 식. */
function categoryOptionFinder(category: string) {
  return (
    "[...document.querySelectorAll('label[for]')]" +
    ".find(e => e.getBoundingClientRect().width > 0" +
    " && [...e.querySelectorAll('[data-testid^=categoryItemText_]')]" +
    `.some(name => name.textContent.replace(/\\s+/g, ' ').trim() === ${q(category)}))`
  );
}

/** 열린 카테고리 목록의 이름. 찾는 카테고리가 없을 때 사용자에게 고르게 돌려준다. */
async function categoryNames(page: EditorPage) {
  const raw = await page.js<string>(
    "JSON.stringify([...document.querySelectorAll('[data-testid^=categoryItemText_]')]" +
      ".map(name => name.textContent.replace(/\\s+/g, ' ').trim()).filter(Boolean))",
  );
  return [...new Set(JSON.parse(raw || "[]") as string[])].slice(0, CATEGORIES_MAX);
}

/** 움직이는 발행 설정 창이 멈추고 단추가 드러난 뒤 누른다. */
async function clickStableSettingsControl(page: EditorPage, selector: string) {
  let lastTop: number | null = null;
  let stableSince = 0;
  let point: { x: number; y: number } | null = null;
  const ready = async () => {
    const raw = await page.js<string | null>(
      `(() => { const el = document.querySelector(${q(selector)});` +
        " if (!el) return null; el.scrollIntoView({block:'nearest'});" +
        " const r = el.getBoundingClientRect();" +
        " if (!r.width || !r.height) return null;" +
        " const x = r.left + r.width / 2, y = r.top + r.height / 2;" +
        " const hit = document.elementFromPoint(x, y);" +
        " return JSON.stringify({top:r.top, x, y, uncovered:!!(hit && (el === hit || el.contains(hit)))}); })()",
    );
    if (!raw) {
      lastTop = null;
      return false;
    }
    const current = JSON.parse(raw) as { top: number; x: number; y: number; uncovered: boolean };
    if (!current.uncovered) {
      lastTop = null;
      return false;
    }
    const now = performance.now();
    if (lastTop !== null && Math.abs(current.top - lastTop) < 0.5) {
      if (now - stableSince >= page.step(0.2) * 1000) {
        point = current;
        return true;
      }
    } else {
      lastTop = current.top;
      stableSince = now;
    }
    return false;
  };
  if (!(await page.waitUntil(ready, page.step(5))) || !point) return false;
  const { x, y } = point;
  await page.mouseAt(x, y);
  return true;
}

/** 카테고리와 태그를 발행 설정에 넣고 설정만 닫는다. */
export async function settings(page: EditorPage, input: DraftInput, draftHash: string) {
  await setStage(page, draftHash, "settings", false);
  const category = input.category.trim();
  const tags = draftTags(input);
  const note = await requireClearScreen(page);
  if (note) throw page.fail("editor_failed", `화면을 덮은 알림이 있어 설정하지 못한다: ${note}`);
  if (!(await openSettings(page))) throw page.fail("editor_failed", "발행 설정을 열지 못했다");
  if (!(await clickStableSettingsControl(page, CATEGORY_BUTTON)))
    throw page.fail("editor_failed", "카테고리 선택기를 열지 못했다");
  const label = categoryOptionFinder(category);
  if (!(await page.waitUntil(async () => Boolean(await page.js(`!!(${label})`)), page.step(10))))
    throw page.fail("category_not_found", "그 이름의 카테고리가 없다", {
      categories: await categoryNames(page),
    });
  if (!(await page.mouseClick(label)))
    throw page.fail("editor_failed", "카테고리 항목을 누르지 못했다");
  if (!(await page.waitUntil(async () => (await settingsState(page)).category === category)))
    throw page.fail("editor_failed", "카테고리 선택이 반영되지 않았다");

  for (const tag of tags) {
    if (!(await clickStableSettingsControl(page, TAG_INPUT)))
      throw page.fail("editor_failed", "태그 입력칸을 누르지 못했다");
    await page.insertText(tag);
    const typed = await page.waitUntil(
      async () => (await page.js(`document.querySelector(${q(TAG_INPUT)})?.value`)) === tag,
    );
    if (!typed) throw page.fail("editor_failed", "태그 글자가 입력되지 않았다");
    await page.sleep(page.step(SETTLE_SECONDS));
    await page.enter();
    if (!(await page.waitUntil(async () => (await settingsState(page)).tags.includes(tag))))
      throw page.fail("editor_failed", "태그 칩이 생기지 않았다");
  }
  const got = await settingsState(page);
  if (got.category !== category || tags.some((tag) => !got.tags.includes(tag)))
    throw page.fail("editor_failed", "카테고리나 태그가 화면과 다르다");
  if (!(await closeSettings(page))) throw page.fail("editor_failed", "발행 설정을 닫지 못했다");
  await setStage(page, draftHash, "settings", true);
}

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
  if (current.draftHash !== draftHash) return ["이 탭에서 검사한 초안과 저장할 초안이 다르다"];
  const missing = STAGES.filter((stage) => !(current.passed ?? []).includes(stage));
  if (missing.length) return [`끝나지 않은 단계: ${missing.join(", ")}`];

  const problems: string[] = [];
  const title = normalize((await paragraphs(page, TITLE_SELECTOR)).join(""));
  if (title !== normalize(input.title)) problems.push("제목이 초안과 다르다");
  const body = (await paragraphs(page, BODY_SELECTOR)).map(normalize).filter(Boolean);
  const wantedText = blocks
    .flatMap((block) => (block.type === "text" ? [normalize(block.line)] : []))
    .filter(Boolean);
  let cursor = 0;
  for (const line of body) if (cursor < wantedText.length && line === wantedText[cursor]) cursor++;
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
      problems.push(`${name} 수가 다르다: 초안 ${expected[name]}, 화면 ${actual[name]}`);
  problems.push(...componentProblems(blocks, await componentState(page), body));
  const fit = (await page.js<number>(FITTED_COUNT)) || 0;
  if (fit !== expected.사진)
    problems.push(`문서 너비 사진 수가 다르다: 초안 ${expected.사진}, 화면 ${fit}`);

  if (!(await openSettings(page))) problems.push("카테고리와 태그를 읽을 수 없다");
  else {
    const state = await settingsState(page);
    if (state.category !== input.category.trim()) problems.push("카테고리가 초안과 다르다");
    const tags = draftTags(input);
    if (state.tags.length !== new Set(tags).size || tags.some((tag) => !state.tags.includes(tag)))
      problems.push("태그가 초안과 다르다");
    if (!(await closeSettings(page))) problems.push("발행 설정을 닫지 못했다");
  }
  if (actual.사진 === expected.사진) {
    const incomplete = await incompleteImages(page, actual.사진);
    if (incomplete.length)
      problems.push(`사진 전송이 끝나지 않았다: ${incomplete.map((i) => i + 1).join(", ")}번째`);
  }
  return problems;
}

/**
 * 초안과 화면을 대조한 뒤 임시저장 단추만 누르고 저장 수가 늘었는지 본다.
 * 누르기 직전에 `onStage("save_clicking")` 을 부르고 그것이 끝난 뒤에 누른다.
 * 그 뒤에 난 실패는 저장됐는지 모르므로 모두 `save_unconfirmed` 다.
 */
export async function save(
  page: EditorPage,
  input: DraftInput,
  blocks: Block[],
  draftHash: string,
  onStage: (stage: string) => unknown,
) {
  if (await settingsOpen(page))
    throw page.fail("editor_failed", "발행 설정을 먼저 닫아야 임시저장할 수 있다");
  const problems = await saveReadiness(page, input, blocks, draftHash);
  if (problems.length)
    throw page.fail("editor_failed", `임시저장을 거절했다: ${problems.join("; ")}`);
  const savedBefore = await saveCount(page);
  if (typeof savedBefore !== "number")
    throw page.fail("editor_failed", "임시저장 수를 읽지 못해 저장하지 않는다");
  const photoCount = blocks.filter((block) => block.type === "image").length;
  if (
    (await imageCount(page)) !== photoCount ||
    (await incompleteImages(page, photoCount)).length
  )
    throw page.fail("editor_failed", "저장 직전에 사진 수나 전송 상태가 달라져 저장하지 않는다");
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
    throw page.fail("save_unconfirmed", `저장 단추를 누른 뒤 확인하지 못했다: ${(error as Error).message}`);
  }
  throw page.fail("save_unconfirmed", "저장 단추는 눌렀지만 임시저장 수가 늘지 않았다");
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
  if (!(await openSettings(page)))
    throw page.fail("editor_failed", "카테고리와 태그 상태를 읽을 수 없다");
  const current = await settingsState(page);
  if (!(await closeSettings(page)))
    throw page.fail("editor_failed", "상태를 읽은 뒤 발행 설정을 닫지 못했다");
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
