/**
 * 채팅 마크다운의 remark·rehype 플러그인과 판정 함수다. 기준은 `docs/features/answer-quality/spec.md` 의 REQ17부터 REQ19 다.
 *
 * node 테스트가 `.tsx` 를 읽지 못해 부품과 나눠 둔다.
 */

type MdNode = {
  type: string;
  value?: string;
  children?: MdNode[];
};

type HastNode = {
  type: string;
  value?: string;
  tagName?: string;
  properties?: Record<string, unknown>;
  children?: HastNode[];
};

/** 출처 절을 감싼 `section` 에 다는 클래스다. 부품이 이 클래스로 출처 절의 모양을 고른다. */
export const SOURCE_SECTION_CLASS = "answer-sources";

/** 원문에서 블록의 위치다. remark 가 노드마다 단다. */
type Position = { start: { offset?: number }; end: { offset?: number } };

type MdCode = MdNode & {
  position?: Position;
  data?: { hProperties?: Record<string, unknown> };
};

/**
 * 흐르는 중(`streaming`)이면 닫는 줄이 없는 코드 블록의 `code` 요소에 `data-open` 을 단다. 부품은 이 표시가 있는 mermaid 블록을 코드로 둔다.
 * 흐르는지를 부품 묶음이 아니라 여기서 받아, 답이 끝나도 부품 묶음이 바뀌지 않고 이미 그린 그림이 다시 붙지 않는다.
 */
export function remarkOpenFence(options: { streaming?: boolean } = {}) {
  return (tree: MdNode, file: { value?: unknown }) => {
    // 흐르는 중이 아니면 닫는 줄이 없어도 그려 본다(spec REQ19). 표시를 달지 않는다.
    if (!options.streaming) return;
    const source = String(file.value ?? "");
    const visit = (node: MdCode) => {
      if (node.type === "code") {
        const start = node.position?.start.offset;
        const end = node.position?.end.offset;
        if (
          start !== undefined &&
          end !== undefined &&
          isOpenFence(source.slice(start, end))
        ) {
          node.data = {
            ...node.data,
            hProperties: { ...node.data?.hProperties, dataOpen: "true" },
          };
        }
        return;
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  };
}

const SKIPPED = new Set(["pre", "code"]);
const BLOCKS = new Set([
  "p",
  "ul",
  "ol",
  "li",
  "blockquote",
  "table",
  "pre",
  "div",
  "section",
  "hr",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
]);

function isBlock(node: HastNode | undefined): boolean {
  return node?.type === "element" && BLOCKS.has(node.tagName ?? "");
}

function isBreak(node: HastNode | undefined): boolean {
  return node?.type === "element" && node.tagName === "br";
}

/**
 * 글 안의 줄바꿈을 `br` 로 바꾼다. 블록 요소를 만나면 그 안으로 `visit` 을 다시 부른다.
 * 블록 경계와 맨 앞뒤의 `br` 은 글을 담는 요소(`top`)에서만 뺀다. 흐림 효과의 `span` 처럼 안으로 들어간 인라인 요소의 끝 `br` 은 두 줄을 나누는 줄바꿈이라 남긴다.
 */
function splitSoftBreaks(node: HastNode, top: boolean): void {
  const next: HastNode[] = [];
  for (const child of node.children ?? []) {
    if (child.type === "text" && child.value?.includes("\n")) {
      child.value.split("\n").forEach((line, index) => {
        if (index > 0)
          next.push({
            type: "element",
            tagName: "br",
            properties: {},
            children: [],
          });
        if (line !== "") next.push({ type: "text", value: line });
      });
      continue;
    }
    if (isBlock(child)) visit(child);
    else if (child.type === "element" && !SKIPPED.has(child.tagName ?? ""))
      splitSoftBreaks(child, false);
    next.push(child);
  }
  if (!top) {
    node.children = next;
    return;
  }
  node.children = next.filter(
    (child, index) =>
      !(
        isBreak(child) &&
        (index === 0 ||
          index === next.length - 1 ||
          isBlock(next[index - 1]) ||
          isBlock(next[index + 1]))
      ),
  );
}

const PHRASING_PARENTS = new Set([
  "p",
  "li",
  "td",
  "th",
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
]);

function visit(node: HastNode): void {
  if (SKIPPED.has(node.tagName ?? "")) return;
  if (PHRASING_PARENTS.has(node.tagName ?? "")) splitSoftBreaks(node, true);
  else node.children?.forEach(visit);
}

/**
 * 줄바꿈 한 번을 `br` 로 바꾼다. 코드 안은 건드리지 않고, GFM 표 칸에는 줄바꿈이 없다. `remark-breaks` 와 같은 결과다.
 * 흐림 플러그인 뒤에 돈다. 흐림은 글자의 원문 위치로 구간을 찾아, 위치 없는 노드를 앞에 만들면 효과가 깨진다.
 * 블록 사이의 줄바꿈(최상위와 목록 사이의 `"\n"` 노드)은 글을 담는 요소 밖이라 바꾸지 않는다.
 */
export function rehypeSoftBreaks() {
  return (tree: HastNode) => visit(tree);
}

const HEADING = /^h[1-6]$/;

function textOf(node: HastNode): string {
  if (node.type === "text") return node.value ?? "";
  return (node.children ?? []).map(textOf).join("");
}

function isHeading(node: HastNode): boolean {
  return node.type === "element" && HEADING.test(node.tagName ?? "");
}

/** 글이 「출처」 인 제목인가. 끝의 콜론은 받는다. 굵은 글자 줄은 제목이 아니다. */
function isSourceHeading(node: HastNode): boolean {
  return isHeading(node) && /^출처\s*[:：]?$/.test(textOf(node).trim());
}

/**
 * 최상위에서 글이 「출처」 인 제목부터 다음 제목 앞까지(없으면 끝까지)를 `section` 하나로 감싼다.
 * 제목의 수준은 묻지 않는다. 출처 절이 둘 이상이면 각각 감싼다.
 */
export function rehypeSourceSection() {
  return (tree: HastNode) => {
    const children = tree.children ?? [];
    const next: HastNode[] = [];
    let section: HastNode | null = null;
    for (const child of children) {
      if (isSourceHeading(child)) {
        section = {
          type: "element",
          tagName: "section",
          properties: { className: [SOURCE_SECTION_CLASS] },
          children: [child],
        };
        next.push(section);
        continue;
      }
      if (section && isHeading(child)) section = null;
      if (section) section.children!.push(child);
      else next.push(child);
    }
    tree.children = next;
  };
}

/**
 * 코드 블록 원문이 닫는 줄 없이 끝났는가. 흐르는 답의 mermaid 블록을 그리기 전에 본다.
 * 여는 줄과 같은 글자로 같은 길이 이상인 줄이 마지막에 있으면 닫힌 것이다. 들여쓴 코드처럼 여는 줄이 없으면 닫힌 것으로 본다.
 */
export function isOpenFence(raw: string): boolean {
  const lines = raw.replace(/\s+$/, "").split(/\r?\n/);
  const open = /^\s*(`{3,}|~{3,})/.exec(lines[0] ?? "");
  if (!open) return false;
  if (lines.length < 2) return true;
  const close = /^\s*(`{3,}|~{3,})\s*$/.exec(lines.at(-1) ?? "");
  return !(
    close &&
    close[1][0] === open[1][0] &&
    close[1].length >= open[1].length
  );
}
