import type { DraftInput } from "../draft.ts";
import { LAYER_DIAGNOSIS_JS } from "./fields.ts";
import {
  ABORTED,
  click,
  EditorError,
  type EditorPage,
  requireClearScreen,
  SETTLE_SECONDS,
  setStage,
} from "./page.ts";

// 상단의 발행 단추는 발행 설정 레이어를 열고 닫기만 한다.
// 설정 안의 발행 확인 단추 `tpb*i.publish` 는 누르지 않는다.
const PUBLISH_SETTINGS_BUTTON = 'button[data-click-area="tpb.publish"]';
const CATEGORY_BUTTON = 'button[data-click-area="tpb*i.category"]';
const TAG_INPUT = "#tag-input";
export const SAVE_BUTTON = "저장";
const CATEGORIES_MAX = 50;
export const SAVE_TRIES = 25;
const OPEN_TRIES = 3;
const DIAGNOSIS_LAYERS = 8;

// 끝이 있는 애니메이션과 전환(패널이 열리는 중)이 도는지 읽는 식. 깜박이는 커서 같은 무한 반복은 뺀다.
const ANIMATING =
  "document.getAnimations().some(a => a.playState === 'running'" +
  " && a.effect?.getComputedTiming().iterations !== Infinity)";

// 요소를 태그와 앞 클래스 둘로만 적는다. 바뀌는 해시가 붙어도 어느 레이어인지는 알아볼 수 있다.
// 편집기 도구 막대 안의 layer 클래스는 늘 보이므로 레이어 목록에서 뺀다.
const PUBLISH_DIAGNOSIS = `(() => {
  ${LAYER_DIAGNOSIS_JS}
  const button = document.querySelector(${JSON.stringify(PUBLISH_SETTINGS_BUTTON)});
  let state = "missing", covered = null;
  if (button) {
    const r = button.getBoundingClientRect();
    if (!r.width || !r.height) state = "hidden";
    else {
      state = button.disabled ? "disabled" : "shown";
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      if (!hit || !(hit === button || button.contains(hit))) covered = name(hit);
    }
  }
  const layers = visibleLayers(${DIAGNOSIS_LAYERS});
  return JSON.stringify({ publish_button: state, covered_by: covered,
    layers });
})()`;

const q = (value: unknown) => JSON.stringify(value);

/** 초안의 태그를 편집기 칩과 같은 모양으로 맞춘다. */
export const draftTags = (input: DraftInput) =>
  input.tags.map((tag) => tag.replace(/^#+/, "").trim()).filter(Boolean);

/** 접힌 설정도 화면을 가리므로 발행 설정 레이어가 있는지를 읽는다. */
export async function settingsOpen(page: EditorPage) {
  return Boolean(
    await page.js("!!document.querySelector('[class^=layer_publish]')"),
  );
}

/**
 * 발행 설정 레이어만 연다.
 * 운영처럼 CDP 왕복이 빠르면 사진을 넣은 직후 편집기가 아직 움직이는 동안 단추를 누르게 된다. 그때 레이어가 열리지 않은 일이 있었다.
 * 끝이 있는 애니메이션이 멈추기를 잠시 기다리고, 단추가 덮이지 않은 채 제자리에 있을 때 누른다. 레이어가 뜨지 않으면 몇 번 다시 누른다.
 * 누를 때마다 먼저 열렸는지 보므로 늦게 열린 레이어를 다시 눌러 닫지 않는다.
 */
export async function openSettings(page: EditorPage) {
  for (let i = 0; i < OPEN_TRIES; i++) {
    if (await settingsOpen(page)) return true;
    await page.waitUntil(async () => !(await page.js(ANIMATING)), page.step(5));
    if (await settingsOpen(page)) return true;
    if (!(await clickStableControl(page, PUBLISH_SETTINGS_BUTTON))) continue;
    if (await page.waitUntil(() => settingsOpen(page))) return true;
  }
  return false;
}

/**
 * 발행 단추를 열지 못한 까닭을 클래스 이름으로만 읽는다. 글과 주소는 싣지 않는다.
 * `publish_button` 은 단추의 상태, `covered_by` 는 단추 가운데를 차지한 요소, `layers` 는 보이는 레이어다.
 */
async function publishDiagnosis(page: EditorPage) {
  try {
    const raw = await page.js<string>(PUBLISH_DIAGNOSIS);
    return JSON.parse(raw || "{}") as Record<string, unknown>;
  } catch (error) {
    if (error instanceof EditorError && error.message === ABORTED) throw error;
    return {};
  }
}

/** 연 단추를 다시 눌러 설정 레이어만 닫는다. */
export async function closeSettings(page: EditorPage) {
  if (!(await settingsOpen(page))) return true;
  if (!(await click(page, PUBLISH_SETTINGS_BUTTON))) return false;
  return page.waitUntil(async () => !(await settingsOpen(page)));
}

/** 열린 발행 설정의 카테고리와 태그를 읽는다. */
export async function settingsState(page: EditorPage) {
  const raw = await page.js<string>(
    "JSON.stringify({" +
      ` category: document.querySelector(${q(CATEGORY_BUTTON)})?.textContent.trim() || '',` +
      " tags: [...document.querySelectorAll('span[id^=\"tag-item-\"][aria-label]')]" +
      "   .map(e => e.getAttribute('aria-label'))" +
      "})",
  );
  const state = JSON.parse(raw || "{}") as {
    category?: string;
    tags?: string[];
  };
  return {
    category: state.category ?? "",
    tags: [...new Set(state.tags ?? [])],
  };
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
  return [...new Set(JSON.parse(raw || "[]") as string[])].slice(
    0,
    CATEGORIES_MAX,
  );
}

/** 움직이는 화면이 멈추고 단추가 드러난 뒤 누른다. */
async function clickStableControl(page: EditorPage, selector: string) {
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
    const current = JSON.parse(raw) as {
      top: number;
      x: number;
      y: number;
      uncovered: boolean;
    };
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
export async function settings(
  page: EditorPage,
  input: DraftInput,
  draftHash: string,
) {
  await setStage(page, draftHash, "settings", false);
  const category = input.category.trim();
  const tags = draftTags(input);
  const note = await requireClearScreen(page);
  if (note)
    throw page.fail("editor_failed", "화면을 덮은 알림이 있어 설정하지 못한다");
  if (!(await openSettings(page)))
    throw page.fail("editor_failed", "발행 설정을 열지 못했다", {
      diagnosis: await publishDiagnosis(page),
    });
  if (!(await clickStableControl(page, CATEGORY_BUTTON)))
    throw page.fail("editor_failed", "카테고리 선택기를 열지 못했다");
  const label = categoryOptionFinder(category);
  if (
    !(await page.waitUntil(
      async () => Boolean(await page.js(`!!(${label})`)),
      page.step(10),
    ))
  )
    throw page.fail("category_not_found", "그 이름의 카테고리가 없다", {
      categories: await categoryNames(page),
    });
  if (!(await page.mouseClick(label)))
    throw page.fail("editor_failed", "카테고리 항목을 누르지 못했다");
  if (
    !(await page.waitUntil(
      async () => (await settingsState(page)).category === category,
    ))
  )
    throw page.fail("editor_failed", "카테고리 선택이 반영되지 않았다");

  for (const tag of tags) {
    if (!(await clickStableControl(page, TAG_INPUT)))
      throw page.fail("editor_failed", "태그 입력칸을 누르지 못했다");
    await page.insertText(tag);
    const typed = await page.waitUntil(
      async () =>
        (await page.js(`document.querySelector(${q(TAG_INPUT)})?.value`)) ===
        tag,
    );
    if (!typed) throw page.fail("editor_failed", "태그 글자가 입력되지 않았다");
    await page.sleep(page.step(SETTLE_SECONDS));
    await page.enter();
    if (
      !(await page.waitUntil(async () =>
        (await settingsState(page)).tags.includes(tag),
      ))
    )
      throw page.fail("editor_failed", "태그 칩이 생기지 않았다");
  }
  const got = await settingsState(page);
  if (got.category !== category || tags.some((tag) => !got.tags.includes(tag)))
    throw page.fail("editor_failed", "카테고리나 태그가 화면과 다르다");
  if (!(await closeSettings(page)))
    throw page.fail("editor_failed", "발행 설정을 닫지 못했다");
  await setStage(page, draftHash, "settings", true);
}

/** 발행 설정을 열어 카테고리와 태그를 읽고 설정만 닫는다. 열거나 닫지 못하면 `editor_failed` 다. */
export async function readSettings(page: EditorPage) {
  if (!(await openSettings(page)))
    throw page.fail("editor_failed", "카테고리와 태그 상태를 읽을 수 없다");
  const current = await settingsState(page);
  if (!(await closeSettings(page)))
    throw page.fail("editor_failed", "상태를 읽은 뒤 발행 설정을 닫지 못했다");
  return current;
}
