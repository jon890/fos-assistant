import { fieldCandidates } from "./fields.ts";
import {
  BODY_SELECTOR,
  SETTLE_SECONDS,
  TITLE_SELECTOR,
  type EditorPage,
} from "./page.ts";

const q = (value: unknown) => JSON.stringify(value);

/** 본문의 SmartEditor 구성요소 수. */
export async function componentCount(page: EditorPage, kind: string) {
  return (
    (await page.js<number>(
      `document.querySelectorAll(".se-component.se-${kind}").length`,
    )) || 0
  );
}

/** 지금 초점이 있는 곳의 글자를 지운다. 전체 선택은 Linux Chrome 의 Control+A 다. */
export async function clearField(page: EditorPage) {
  await page.press("a", "KeyA", 65, 2);
  await page.press("Delete", "Delete", 46);
}

// 이모지와 그것을 잇는 변형 선택자, 피부색, ZWJ 를 한 덩어리로 잡는다.
const EMOJI_RUN =
  /([\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}]+)/u;

/** 글자와 이모지를 나눠 넣을 조각. 한 번에 넣으면 앞 글자가 사라진다. */
export function emojiSplit(text: string) {
  return text.split(EMOJI_RUN).filter(Boolean);
}

/** 편집기가 바꿔 넣는 공백 문자를 되돌려 초안과 견줄 수 있게 한다. */
export function normalize(text: string) {
  return text
    .replace(/\u00a0/g, " ")
    .replace(/[\u200b\ufeff]/g, "")
    .trim();
}

/** 선택자에 걸리는 문단의 글을 차례대로 읽는다. 빈 칸의 안내 문구는 실제 글이 아니라 뺀다. */
export async function paragraphs(
  page: EditorPage,
  selector: string,
): Promise<string[]> {
  const raw = await page.js<string>(
    `(() => { ${selector === TITLE_SELECTOR ? fieldCandidates(selector, true) : ""}
    return JSON.stringify((${selector === TITLE_SELECTOR ? "el ? [el] : []" : `[...document.querySelectorAll(${q(selector)})]`})` +
      ".map(e => { const c = e.cloneNode(true);" +
      " c.querySelectorAll('.se-placeholder').forEach(x => x.remove());" +
      " return c.textContent; })); })()",
  );
  return JSON.parse(raw || "[]");
}

/**
 * 커서가 있는 마지막 문단에 한 줄을 넣고 반영될 때까지 기다린다.
 * 글자와 이모지를 나눠 넣고 조각마다 화면에 들어왔는지 확인한 뒤 잠깐 둔다.
 */
export async function typeLine(
  page: EditorPage,
  selector: string,
  text: string,
) {
  let typed = "";
  for (const part of emojiSplit(text)) {
    await page.insertText(part);
    typed += part;
    const want = normalize(typed);
    await page.waitUntil(async () => {
      const lines = await paragraphs(page, selector);
      return normalize(lines[lines.length - 1] ?? "") === want;
    });
    await page.sleep(page.step(SETTLE_SECONDS));
  }
}

/** Enter 를 누르고 문단이 하나 늘어날 때까지 기다린다. */
export async function newParagraph(page: EditorPage, selector: string) {
  const count = (await paragraphs(page, selector)).length;
  await page.enter();
  await page.waitUntil(
    async () => (await paragraphs(page, selector)).length > count,
  );
}

/** 본문에서 자리표시 문단을 찾아 초점을 두고 글자를 지운다. */
export async function focusPlaceholder(page: EditorPage, text: string) {
  const finder =
    `[...document.querySelectorAll(${q(BODY_SELECTOR)})]` +
    `.filter(e => { const value = e.textContent.trim();` +
    ` return value === ${q(text)} ||` +
    ` (${q(text)}.startsWith(value) && value.length >= ${text.length - 10}); })`;
  const matches = JSON.parse(
    (await page.js<string>(
      `JSON.stringify(${finder}.map(e => ({id:e.id, text:e.textContent.trim()})))`,
    )) || "[]",
  ) as Array<{ id: string; text: string }>;
  if (matches.length !== 1 || !matches[0]!.id) return false;
  const elementId = matches[0]!.id;
  const remaining = matches[0]!.text;
  const element = `document.getElementById(${q(elementId)})`;
  const currentText = async () =>
    (await page.js<string>(`${element}?.textContent`)) || "";

  // 문단 끝 글자의 오른쪽을 눌러 커서를 문단 끝에 둔다.
  const clickEnd = async () => {
    const spot = await page.js<string | null>(`(() => {
  const el = ${element};
  if (!el) return null;
  el.scrollIntoView({behavior: "instant", block: "center"});
  const range = document.createRange();
  range.selectNodeContents(el);
  const rects = range.getClientRects();
  const rect = rects[rects.length - 1];
  if (!rect) return null;
  return JSON.stringify({x: rect.right + 4, y: rect.top + rect.height / 2});
})()`);
    if (!spot) return false;
    const point = JSON.parse(spot) as { x: number; y: number };
    await page.mouseAt(point.x, point.y);
    return Boolean(
      await page.js(
        "(() => { const s = getSelection(); const n = s.anchorNode;" +
          ` return n?.nodeType === 3 && n.parentElement.closest(${q(BODY_SELECTOR)})?.id === ${q(elementId)}` +
          " && s.anchorOffset === n.textContent.length; })()",
      ),
    );
  };

  if (!(await clickEnd())) return false;
  // JS Range 로 선택해 지우면 편집기 내부 커서와 어긋날 수 있다.
  // 실제 키 입력으로 끝에서 지우고, 매번 줄이 짧아졌는지 확인한다.
  for (let i = 0; i < remaining.length * 2; i++) {
    const current = await currentText();
    if (!current) return true;
    if (current.length > remaining.length || !remaining.startsWith(current))
      return false;
    if (!(await clickEnd())) return false;
    await page.press("Backspace", "Backspace", 8);
    if (
      !(await page.waitUntil(
        async () => (await currentText()).length < current.length,
      ))
    )
      return false;
    await page.sleep(page.step(SETTLE_SECONDS));
  }
  return false;
}
