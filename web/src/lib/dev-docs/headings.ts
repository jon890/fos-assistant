import GithubSlugger from "github-slugger";

/**
 * 문서 하나의 제목 id 를 만든다. 호출마다 새 slugger 로 시작해 같은 제목의 `-1` 이 GitHub 와 같게 붙는다.
 * 문서들이 GitHub 규칙의 `#절`(한글 포함)로 서로 가리켜 같은 규칙이어야 맞는다.
 */
export function createHeadingIds(): (text: string) => string {
  const slugger = new GithubSlugger();
  return (text) => slugger.slug(text);
}

/** rehype 가 넘기는 HTML 트리의 마디다. 쓰는 칸만 적는다. */
type HastNode = {
  type: string;
  tagName?: string;
  value?: string;
  properties?: Record<string, unknown>;
  children?: HastNode[];
};

/** 마디 아래의 글자를 모은다. 제목 안의 코드나 링크도 글자로 센다. */
export function hastText(node: HastNode): string {
  if (node.type === "text") return node.value ?? "";
  return (node.children ?? []).map(hastText).join("");
}

/**
 * 제목(h1~h6)에 GitHub 와 같은 id 를 붙이는 rehype 플러그인이다. 문서를 처리할 때마다 새로 센다.
 * 렌더 중에 세면 React 가 부품을 두 번 부를 때(개발 모드) `-1` 이 붙어 서버 HTML 과 어긋난다.
 */
export function rehypeHeadingIds() {
  return (tree: HastNode) => {
    const idOf = createHeadingIds();
    const walk = (node: HastNode) => {
      if (node.type === "element" && /^h[1-6]$/.test(node.tagName ?? "")) {
        node.properties = { ...node.properties, id: idOf(hastText(node)) };
      }
      node.children?.forEach(walk);
    };
    walk(tree);
  };
}
