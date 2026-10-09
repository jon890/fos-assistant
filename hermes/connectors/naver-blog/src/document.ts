/**
 * 편집기의 `getDocumentData()` 가 돌려주는 문서. 실측으로 확인한 칸만 적는다.
 * 제목은 `documentTitle` 구성요소의 `title` 문단, 글은 `text` 구성요소의 `value` 문단이고 문단의 `nodes` 가 글 조각이다.
 */
type DocumentNode = { value?: unknown };
type DocumentParagraph = { nodes?: DocumentNode[] };
export type DocumentComponent = {
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
  const flat = (text: string) => text.replace(/[\r\n]+/g, " ");
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
