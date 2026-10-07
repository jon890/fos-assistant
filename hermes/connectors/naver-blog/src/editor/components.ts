import type { Block } from "../draft.ts";
import {
  BODY_SELECTOR,
  clearField,
  click,
  componentCount,
  type EditorPage,
  focusPlaceholder,
  normalize,
  paragraphs,
  requireClearScreen,
  setStage,
} from "./page.ts";
import { mapPlaceholder, stickerPlaceholder } from "./text.ts";

type StickerBlock = Extract<Block, { type: "sticker" }>;
type MapBlock = Extract<Block, { type: "map" }>;
export type Place = { name: string; address: string };
type Candidate = Place & { index: number };

const STICKER_BUTTON = "button.se-sticker-toolbar-button";
const PLACE_BUTTON = "button.se-map-toolbar-button";
const PLACE_SEARCH_INPUT = 'input[placeholder="장소명을 입력하세요."]';
const PLACE_MODE_BUTTON = ".se-popup-label-select-button";
const PLACE_RESULT_LINK = ".se-place-map-search-result-link";
const CANDIDATES_MAX = 10;

const q = (value: unknown) => JSON.stringify(value);

/** 시도 정식 이름과 흔한 줄임 표기를 줄임 표기 하나로 모은다. 17개 시도와 바뀌기 전 이름을 담는다. */
const REGION_SHORT_NAMES: Record<string, string> = Object.fromEntries(
  (
    [
      ["서울", ["서울특별시", "서울시"]],
      ["부산", ["부산광역시", "부산시"]],
      ["대구", ["대구광역시", "대구시"]],
      ["인천", ["인천광역시", "인천시"]],
      ["광주", ["광주광역시"]],
      ["대전", ["대전광역시", "대전시"]],
      ["울산", ["울산광역시", "울산시"]],
      ["세종", ["세종특별자치시", "세종시"]],
      ["경기", ["경기도"]],
      ["강원", ["강원특별자치도", "강원도"]],
      ["충북", ["충청북도"]],
      ["충남", ["충청남도"]],
      ["전북", ["전북특별자치도", "전라북도"]],
      ["전남", ["전라남도"]],
      ["경북", ["경상북도"]],
      ["경남", ["경상남도"]],
      ["제주", ["제주특별자치도", "제주도"]],
    ] as const
  ).flatMap(([short, names]) => names.map((name) => [name, short])),
);

/**
 * 네이버 지도와 초안이 다르게 쓰는 지역 표기를 맞춘다.
 * 공백을 하나로 모으고, 앞의 `대한민국` 을 떼고, 첫 낱말이 시도 이름이면 줄임 표기로 바꾼다.
 */
export function normalizePlaceAddress(text: string) {
  const words = normalize(text).split(/\s+/).filter(Boolean);
  if (words[0] === "대한민국") words.shift();
  if (words[0] && REGION_SHORT_NAMES[words[0]]) words[0] = REGION_SHORT_NAMES[words[0]]!;
  return words.join(" ");
}

/** 지도 검색 결과와 카드 주소 끝에 반복된 상호명을 뗀다. */
export function placeAddressWithoutRepeatedName(name: string, address: string) {
  const value = normalize(address);
  const suffix = ` ${normalize(name)}`;
  return suffix.trim() && value.endsWith(suffix) ? value.slice(0, -suffix.length) : value;
}

/** 자리표시 문단에 코드로 지정한 스티커를 넣는다. 스티커 창의 보이는 단추 가운데 글자가 코드와 같은 것을 누른다. */
async function insertSticker(page: EditorPage, block: StickerBlock) {
  const code = block.code;
  const marker = stickerPlaceholder(block);
  if (!(await page.waitUntil(() => focusPlaceholder(page, marker), page.step(5))))
    throw page.fail("editor_failed", `스티커 자리를 찾지 못했다: ${code}`);
  const before = await componentCount(page, "sticker");
  const panelOpen = await page.js(
    "(() => { const e = document.querySelector('.se-sidebar-container-sticker');" +
      " if (!e) return false; const r = e.getBoundingClientRect();" +
      " return r.width > 0 && r.height > 0; })()",
  );
  if (!panelOpen && !(await click(page, STICKER_BUTTON)))
    throw page.fail("editor_failed", "스티커 단추를 찾지 못했다");
  const finder =
    "[...document.querySelectorAll('button.se-sidebar-element-sticker')]" +
    `.find(e => e.getBoundingClientRect().width > 0 && e.innerText.trim() === ${q(code)})`;
  if (!(await page.waitUntil(async () => Boolean(await page.js(`!!(${finder})`)))))
    throw page.fail("editor_failed", `스티커 창에서 코드를 찾지 못했다: ${code}`);
  if (!(await page.mouseClick(finder)))
    throw page.fail("editor_failed", `스티커를 누르지 못했다: ${code}`);
  if (!(await page.waitUntil(async () => (await componentCount(page, "sticker")) > before)))
    throw page.fail("editor_failed", `스티커가 본문에 들어가지 않았다: ${code}`);
}

/** 장소 검색 결과의 이름과 주소를 화면 순서대로 읽는다. */
async function placeCandidates(page: EditorPage): Promise<Candidate[]> {
  const raw = await page.js<string>(
    `JSON.stringify([...document.querySelectorAll(${q(PLACE_RESULT_LINK)})]` +
      ".map((link, index) => {" +
      " const row = link.closest('li') || link.parentElement;" +
      " const lines = (row?.innerText || '').split('\\n').map(v => v.trim()).filter(Boolean);" +
      " return {index, name: lines[0] || '', address: lines.slice(1).join(' ')};" +
      "}))",
  );
  const candidates = JSON.parse(raw || "[]") as Candidate[];
  return candidates.map((item) => ({
    ...item,
    address: placeAddressWithoutRepeatedName(item.name, item.address),
  }));
}

const placeMode = (page: EditorPage) =>
  page.js<string | undefined>(`document.querySelector(${q(PLACE_MODE_BUTTON)})?.innerText.trim()`);

/** 장소 검색 범위를 국내로 고른다. 편집기가 이전 선택을 기억할 수 있다. */
async function ensureDomesticMap(page: EditorPage) {
  if (!(await page.waitUntil(async () => Boolean(await placeMode(page)))))
    return "지도 검색 범위를 찾지 못했다";
  if ((await placeMode(page)) === "국내") return "";
  if (
    !(await click(page, PLACE_MODE_BUTTON)) ||
    !(await click(page, "label[for=popup-select-option-domestic]"))
  )
    return "지도 검색을 국내로 바꾸지 못했다";
  if (!(await page.waitUntil(async () => (await placeMode(page)) === "국내")))
    return "지도 검색이 국내로 바뀌지 않았다";
  return "";
}

/** 검색 범위를 바꾼 직후 잠깐 남는 해외 결과를 뺀다. */
async function domesticPlaceCandidates(page: EditorPage) {
  if ((await placeMode(page)) !== "국내") return [];
  return (await placeCandidates(page)).filter((item) => !item.address.startsWith("대한민국 "));
}

/**
 * 검색어 하나로 장소를 찾고 이번 검색의 국내 결과를 돌려준다.
 * 이전 검색 결과가 잠깐 남아 있으므로 목록이 바뀔 때까지 기다린다.
 * 새 결과가 이전 결과와 같으면 목록이 바뀌지 않으므로, 기다린 뒤에도 결과가 있으면 그대로 쓴다.
 */
async function searchPlaces(page: EditorPage, query: string) {
  const problem = await ensureDomesticMap(page);
  if (problem) return { problem, found: [] as Candidate[] };
  if (!(await page.waitUntil(async () => Boolean(await page.js(`!!document.querySelector(${q(PLACE_SEARCH_INPUT)})`)))))
    return { problem: "장소 검색 입력칸을 찾지 못했다", found: [] };
  if (!(await click(page, PLACE_SEARCH_INPUT)))
    return { problem: "장소 검색 입력칸을 누르지 못했다", found: [] };
  const previous = q(await domesticPlaceCandidates(page));
  await clearField(page);
  await page.insertText(query);
  await page.enter();
  await page.waitUntil(async () => {
    const current = await domesticPlaceCandidates(page);
    return current.length > 0 && q(current) !== previous;
  }, page.step(5));
  return { problem: "", found: await domesticPlaceCandidates(page) };
}

/**
 * 상호명, 주소 순서로 검색해 이름과 주소가 모두 같은 결과를 찾는다.
 * 네이버 장소 검색은 상호명으로는 안 나오고 주소로는 나오는 경우가 있다.
 * 검색어만 넓히고 고르는 기준은 상호와 주소의 완전 일치를 지킨다.
 */
async function findPlaceMatches(page: EditorPage, name: string, address: string) {
  let candidates: Candidate[] = [];
  let matches: Candidate[] = [];
  for (const query of [name, address]) {
    for (let i = 0; i < 3; i++) {
      const { problem, found } = await searchPlaces(page, query);
      if (problem) throw page.fail("editor_failed", problem);
      if (found.length) {
        candidates = found;
        matches = found.filter(
          (item) =>
            normalize(item.name) === name && normalizePlaceAddress(item.address) === address,
        );
        break;
      }
    }
    if (matches.length) break;
  }
  return { candidates, matches };
}

/** 상호명과 주소가 모두 같은 검색 결과 하나만 골라 지도 카드를 넣는다. 그 결과의 보이는 추가 단추만 누른다. */
async function insertMap(page: EditorPage, block: MapBlock) {
  const name = normalize(block.name);
  const address = normalizePlaceAddress(block.address);
  const place = { name: block.name, address: block.address };
  if (!name || !address) throw page.fail("editor_failed", "지도 줄의 상호명과 주소를 모두 채운다");
  const marker = mapPlaceholder(block);
  if (!(await page.waitUntil(() => focusPlaceholder(page, marker), page.step(5))))
    throw page.fail("editor_failed", "장소 자리를 찾지 못했다");
  const before = await componentCount(page, "placesMap");
  if (!(await click(page, PLACE_BUTTON))) throw page.fail("editor_failed", "장소 단추를 찾지 못했다");
  const { candidates, matches } = await findPlaceMatches(page, name, address);
  if (matches.length !== 1)
    throw page.fail("place_not_unique", "상호명과 주소가 모두 맞는 장소가 하나가 아니다", {
      place,
      candidates: candidates
        .slice(0, CANDIDATES_MAX)
        .map((item) => ({ name: item.name, address: item.address })),
    });
  const finder = `document.querySelectorAll(${q(PLACE_RESULT_LINK)})[${matches[0]!.index}]`;
  if (!(await page.mouseClick(finder)))
    throw page.fail("editor_failed", "장소 검색 결과를 누르지 못했다");
  const addFinder =
    "[...document.querySelectorAll('.se-place-add-button')]" +
    ".find(e => e.getBoundingClientRect().width > 0 && !e.disabled)";
  if (!(await page.waitUntil(async () => Boolean(await page.js(`!!(${addFinder})`)))))
    throw page.fail("editor_failed", "장소 추가 단추가 나타나지 않았다");
  if (!(await page.mouseClick(addFinder)))
    throw page.fail("editor_failed", "장소 추가 단추를 누르지 못했다");
  const confirmFinder =
    "[...document.querySelectorAll('.se-popup-button-confirm')]" +
    ".find(e => !e.disabled && e.getBoundingClientRect().width > 0)";
  if (!(await page.waitUntil(async () => Boolean(await page.js(`!!(${confirmFinder})`)))))
    throw page.fail("editor_failed", "장소 확인 단추가 나타나지 않았다");
  if (!(await page.mouseClick(confirmFinder)))
    throw page.fail("editor_failed", "장소 확인 단추를 누르지 못했다");
  if (
    !(await page.waitUntil(
      async () => (await componentCount(page, "placesMap")) > before,
      page.step(10),
    ))
  )
    throw page.fail("editor_failed", "지도 카드가 본문에 들어가지 않았다");
}

export type ComponentState = { stickers: string[]; maps: string[] };

/** 본문에 들어간 스티커 코드와 지도 카드의 글을 읽는다. 스티커는 보이게 한 뒤 코드를 읽는다. */
export async function componentState(page: EditorPage): Promise<ComponentState> {
  const stickers: string[] = [];
  const count = await componentCount(page, "sticker");
  for (let index = 0; index < count; index++) {
    const visibleCode = async () =>
      (await page.js<string>(
        `(() => { const sticker = [...document.querySelectorAll('.se-component.se-sticker')][${index}];` +
          " if (!sticker) return '';" +
          " sticker.scrollIntoView({behavior: 'instant', block: 'center'});" +
          " return sticker.querySelector('img')?.alt || ''; })()",
      )) || "";
    await page.waitUntil(async () => Boolean(await visibleCode()), page.step(15));
    stickers.push(await visibleCode());
  }
  const maps = JSON.parse(
    (await page.js<string>(
      "JSON.stringify([...document.querySelectorAll('.se-component.se-placesMap')]" +
        ".map(e=>e.innerText))",
    )) || "[]",
  ) as string[];
  return { stickers, maps };
}

/** 스티커 코드 순서와 지도 상호, 주소가 초안과 같은지 대조한다. */
export function componentProblems(blocks: Block[], state: ComponentState, lines: string[]) {
  const problems: string[] = [];
  const wantedStickers = blocks.flatMap((block) => (block.type === "sticker" ? [block.code] : []));
  if (q(state.stickers) !== q(wantedStickers)) problems.push("스티커 코드나 순서가 초안과 다르다");
  const wantedMaps = blocks.flatMap((block) =>
    block.type === "map" ? [[normalize(block.name), normalizePlaceAddress(block.address)]] : [],
  );
  const actualMaps = state.maps
    .map((text) => text.split("\n").map(normalize).filter(Boolean))
    .filter((parts) => parts.length >= 2)
    .map((parts) => [
      parts[0]!,
      normalizePlaceAddress(placeAddressWithoutRepeatedName(parts[0]!, parts[1]!)),
    ]);
  if (q(actualMaps) !== q(wantedMaps)) problems.push("지도 상호명이나 주소가 초안과 다르다");
  if (lines.some((line) => line.startsWith("[스티커 자리:") || line.startsWith("[장소 자리:")))
    problems.push("스티커나 지도 자리표시가 남았다");
  return problems;
}

/** 스티커와 지도 자리를 실제 편집기 구성요소로 바꾸고 화면과 초안을 대조한다. */
export async function components(page: EditorPage, blocks: Block[], draftHash: string) {
  await setStage(page, draftHash, "components", false);
  const note = await requireClearScreen(page);
  if (note) throw page.fail("editor_failed", "화면을 덮은 알림이 있어 구성요소를 넣지 못한다");
  const currentProblems = async () =>
    componentProblems(blocks, await componentState(page), await paragraphs(page, BODY_SELECTOR));
  if ((await currentProblems()).length === 0) {
    await setStage(page, draftHash, "components", true);
    return;
  }
  for (const block of blocks) {
    if (block.type === "sticker") await insertSticker(page, block);
    else if (block.type === "map") await insertMap(page, block);
  }
  await page.waitUntil(async () => (await currentProblems()).length === 0, page.step(10));
  const problems = await currentProblems();
  if (problems.length)
    throw page.fail("editor_failed", `구성요소가 초안과 다르다: ${problems.join(", ")}`);
  await setStage(page, draftHash, "components", true);
}
