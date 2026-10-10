import { randomUUID } from "node:crypto";
import { lineEdits } from "./changes.ts";

/**
 * 편집기의 `getDocumentData()` 가 돌려주는 문서. 실측으로 확인한 칸만 적는다.
 * 제목은 `documentTitle` 구성요소의 `title` 문단, 글은 `text` 구성요소의 `value` 문단이고 문단의 `nodes` 가 글 조각이다.
 */
type DocumentNode = { id?: unknown; value?: unknown; "@ctype"?: unknown };
type DocumentParagraph = { id?: unknown; nodes?: DocumentNode[]; "@ctype"?: unknown };
export type DocumentComponent = {
  id?: unknown;
  layout?: unknown;
  "@ctype"?: unknown;
  title?: DocumentParagraph[];
  value?: DocumentParagraph[];
};
export type EditorDocument = {
  documentId?: unknown;
  document?: { components?: DocumentComponent[] };
};

/** 글이 아닌 기존 구성요소를 본문에서 가리키는 이름. 이 셋 밖의 구성요소는 「구성요소」 다. */
const KIND_NAMES: Record<string, string> = { image: "사진", sticker: "스티커", placesMap: "지도" };
const OTHER_KIND = "구성요소";
const CTYPE = /^[A-Za-z0-9_-]{1,40}$/;

/** 본문의 기존 구성요소 줄. 종류마다 1부터 센 번호를 단다. 「구성요소」 는 편집기의 종류 이름을 덧붙인다. */
export const EXISTING_LINE =
  /^\[기존 (사진|스티커|지도|구성요소) ([1-9][0-9]*)(?:: [A-Za-z0-9_-]{1,40})?\]$/;

export type ExistingComponent = {
  /** 본문에 쓰는 줄. `[기존 사진 1]` 모양이다 */
  line: string;
  /** 문서의 `components` 에서 몇 번째인지(0부터) */
  index: number;
};

export type DocumentDraft = {
  title: string;
  /** 한 줄이 한 문단이다. 기존 구성요소는 `[기존 사진 1]` 같은 줄이다 */
  body: string;
  existing: ExistingComponent[];
};

/** 문서의 모양이 예상과 다르다. 화면이나 문서의 글은 싣지 않는다. */
export class DocumentShapeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DocumentShapeError";
  }
}

/** 줄 안의 줄바꿈을 공백으로 바꿔 한 문단이 한 줄을 지키게 한다. */
const flat = (text: string) => text.replace(/[\r\n]+/g, " ");

/** 문단의 글 조각을 잇는다. 글이 없는 조각은 건너뛴다. */
function paragraphText(paragraph: DocumentParagraph) {
  if (!paragraph || !Array.isArray(paragraph.nodes)) throw new DocumentShapeError("문단에 nodes 가 없다");
  return paragraph.nodes.map((node) => (typeof node?.value === "string" ? node.value : "")).join("");
}

/**
 * 편집기 문서를 제목과 본문 줄로 바꾼다. 글 구성요소의 문단은 한 줄씩, 그 밖의 구성요소는 기존 구성요소 줄 하나가 된다.
 * 줄 안의 줄바꿈은 공백으로 바꿔 한 문단이 한 줄을 지키게 한다. 제목 구성요소가 하나가 아니면 실패한다.
 */
export function documentToDraft(data: EditorDocument): DocumentDraft {
  const components = data?.document?.components;
  if (!Array.isArray(components)) throw new DocumentShapeError("문서에 components 가 없다");
  const titles = components.filter((component) => component?.["@ctype"] === "documentTitle");
  if (titles.length !== 1) throw new DocumentShapeError("제목 구성요소가 하나가 아니다");
  const title = flat((titles[0]!.title ?? []).map(paragraphText).join(""));

  const counts = new Map<string, number>();
  const lines: string[] = [];
  const existing: ExistingComponent[] = [];
  components.forEach((component, index) => {
    const ctype = component?.["@ctype"];
    if (ctype === "documentTitle") return;
    if (ctype === "text") {
      if (!Array.isArray(component.value)) throw new DocumentShapeError("글 구성요소에 value 가 없다");
      for (const paragraph of component.value) lines.push(flat(paragraphText(paragraph)));
      return;
    }
    const name = typeof ctype === "string" ? (KIND_NAMES[ctype] ?? OTHER_KIND) : OTHER_KIND;
    const number = (counts.get(name) ?? 0) + 1;
    counts.set(name, number);
    const label =
      name === OTHER_KIND && typeof ctype === "string" && CTYPE.test(ctype) ? `: ${ctype}` : "";
    const line = `[기존 ${name} ${number}${label}]`;
    lines.push(line);
    existing.push({ line, index });
  });
  return { title, body: lines.join("\n"), existing };
}

/** 편집기 문서에서 실측한 id 모양. */
const newId = () => `SE-${randomUUID()}`;

/** 글 한 줄로 새 문단을 만든다. 꾸밈 칸은 두지 않는다. */
function newParagraph(text: string, paragraphId = newId(), nodeId = newId()): DocumentParagraph {
  return {
    id: paragraphId,
    nodes: [{ id: nodeId, value: text, "@ctype": "textNode" }],
    "@ctype": "paragraph",
  };
}

function newTextComponent(paragraphs: DocumentParagraph[]): DocumentComponent {
  return { id: newId(), layout: "default", value: paragraphs, "@ctype": "text" };
}

/**
 * 원래 문서와 고친 제목, 본문으로 `setDocumentData()` 에 넣을 문서를 만든다. `original` 은 바꾸지 않는다.
 * 기존 구성요소 줄은 원래 구성요소 객체를 그 자리에 두고, 원래 글과 줄 비교로 짝지은 글 줄은 원래 문단 객체를 그대로 써 꾸밈을 지킨다.
 * 본문에 없는 기존 구성요소는 빠진다. 루트의 다른 칸은 뜻을 실측하지 못해 그대로 둔다.
 */
export function draftToDocument(original: EditorDocument, title: string, body: string): EditorDocument {
  const { existing } = documentToDraft(original);
  const result = structuredClone(original);
  const components = result.document!.components!;

  // 원래 글 줄과 그 문단. 기존 구성요소 줄은 짝 대상이 아니라 넣지 않는다.
  const before: { line: string; paragraph: DocumentParagraph }[] = [];
  for (const component of components)
    if (component?.["@ctype"] === "text")
      for (const paragraph of component.value!)
        before.push({ line: flat(paragraphText(paragraph)), paragraph });

  const after = body.split("\n").map((raw) => (raw.endsWith("\r") ? raw.slice(0, -1) : raw));
  const isExisting = after.map((line) => EXISTING_LINE.test(line));
  const indexOf = new Map(existing.map((item) => [item.line, item.index]));
  const used = new Set<string>();
  after.forEach((line, index) => {
    if (!isExisting[index]) return;
    if (!indexOf.has(line) || used.has(line))
      throw new DocumentShapeError("기존 구성요소 줄이 원래 글에 없다");
    used.add(line);
  });

  // 고친 글 줄 차례마다 다시 쓸 원래 문단. 짝짓지 못한 줄은 비어 있다.
  const texts = after.filter((_, index) => !isExisting[index]);
  const reused: (DocumentParagraph | undefined)[] = [];
  let i = 0;
  let j = 0;
  for (const edit of lineEdits(before.map((item) => item.line), texts)) {
    if (edit.kind === "same") reused[j++] = before[i++]!.paragraph;
    else if (edit.kind === "remove") i++;
    else j++;
  }

  const bodyComponents: DocumentComponent[] = [];
  let current: DocumentParagraph[] | undefined;
  let hasText = false;
  let k = 0;
  after.forEach((line, index) => {
    if (isExisting[index]) {
      bodyComponents.push(components[indexOf.get(line)!]!);
      current = undefined;
      return;
    }
    if (!current) {
      current = [];
      bodyComponents.push(newTextComponent(current));
      hasText = true;
    }
    current.push(reused[k] ?? newParagraph(line));
    k++;
  });
  if (!hasText) bodyComponents.push(newTextComponent([newParagraph("")]));

  const titleComponent = components.find((component) => component?.["@ctype"] === "documentTitle")!;
  const first = titleComponent.title?.[0];
  const firstNode = first?.nodes?.[0];
  titleComponent.title = [
    newParagraph(
      title,
      typeof first?.id === "string" ? first.id : newId(),
      typeof firstNode?.id === "string" ? firstNode.id : newId(),
    ),
  ];
  result.document!.components = [titleComponent, ...bodyComponents];
  return result;
}
