import { randomUUID } from "node:crypto";
import type { EditorDocument } from "../document.ts";
import { buttonFinder, type EditorPage } from "./page.ts";
import { closeSettings, openSettings, settingsState } from "./settings.ts";

const q = (value: unknown) => JSON.stringify(value);

/**
 * 편집기에 문서를 통째로 넣는다. 편집기 객체나 `setDocumentData()` 가 없으면 `editor_failed` 다.
 * 문서는 JSON 글을 다시 JSON 문자열로 감싸 식에 싣는다. 식 안에서 코드로 읽히지 않는다.
 */
export async function setDocument(page: EditorPage, doc: EditorDocument) {
  const set = await page.js<boolean>(
    `(() => { const doc = ${q(q(doc))};` +
      " const editors = window.SmartEditor && window.SmartEditor._editors;" +
      " const editor = editors && Object.values(editors)[0];" +
      " if (!editor || typeof editor.setDocumentData !== 'function') return false;" +
      " editor.setDocumentData(JSON.parse(doc)); return true; })()",
  );
  if (set !== true) throw page.fail("editor_failed", "편집기에 문서를 넣지 못했다");
}

/** 저장 단추를 사람이 누른 것처럼 누른다. 단추를 찾지 못하면 `false` 다. */
export function clickSave(page: EditorPage) {
  return page.mouseClick(buttonFinder("button", "저장"));
}

/** 발행 설정에서 그 태그 칩의 지우기 단추를 찾는 식. */
const tagRemoveFinder = (tag: string) =>
  `[...document.querySelectorAll('span[id^="tag-item-"][aria-label]')]` +
  `.find(e => e.getAttribute('aria-label') === ${q(tag)})?.querySelector('button')`;

/**
 * 발행 설정을 열어 태그 칩을 하나씩 지우고 설정만 닫는다.
 * 칩이나 단추를 찾지 못하거나, 누른 뒤에도 칩이 남으면 `editor_failed` 다.
 */
export async function removeTags(page: EditorPage, tags: string[]) {
  if (!tags.length) return;
  if (!(await openSettings(page))) throw page.fail("editor_failed", "발행 설정을 열지 못했다");
  for (const tag of tags) {
    if (!(await page.mouseClick(tagRemoveFinder(tag))))
      throw page.fail("editor_failed", "지울 태그 칩을 찾지 못했다");
    if (!(await page.waitUntil(async () => !(await settingsState(page)).tags.includes(tag))))
      throw page.fail("editor_failed", "태그 칩이 지워지지 않았다");
  }
  if (!(await closeSettings(page))) throw page.fail("editor_failed", "발행 설정을 닫지 못했다");
}

/** 편집기 문서에서 실측한 id 모양. */
const newId = () => `SE-${randomUUID()}`;

/**
 * 문서를 복제해 제목 구성요소의 `title` 만 글 한 문단으로 바꾼다. `doc` 은 바꾸지 않는다.
 * 문단과 첫 글 조각의 id 는 원래 첫 문단의 것을 쓴다. 제목 구성요소가 없으면 복제만 돌려준다.
 */
export function withTitle(doc: EditorDocument, title: string): EditorDocument {
  const result = structuredClone(doc);
  const component = result.document?.components?.find(
    (item) => item?.["@ctype"] === "documentTitle",
  );
  if (!component) return result;
  const first = component.title?.[0];
  const firstNode = first?.nodes?.[0];
  component.title = [
    {
      id: typeof first?.id === "string" ? first.id : newId(),
      nodes: [
        {
          id: typeof firstNode?.id === "string" ? firstNode.id : newId(),
          value: title,
          "@ctype": "textNode",
        },
      ],
      "@ctype": "paragraph",
    },
  ];
  return result;
}
