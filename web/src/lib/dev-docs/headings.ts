import GithubSlugger from "github-slugger";
import { isValidElement, type ReactNode } from "react";

/**
 * 문서 하나의 제목 id 를 만든다. 호출마다 새 slugger 로 시작해 같은 제목의 `-1` 이 GitHub 와 같게 붙는다.
 * 문서들이 GitHub 규칙의 `#절`(한글 포함)로 서로 가리켜 같은 규칙이어야 맞는다.
 */
export function createHeadingIds(): (text: string) => string {
  const slugger = new GithubSlugger();
  return (text) => slugger.slug(text);
}

/** react 노드에서 글자만 모은다. 제목 안의 코드나 링크도 글자로 센다. */
export function headingText(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === "boolean")
    return "";
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(headingText).join("");
  if (isValidElement<{ children?: ReactNode }>(node))
    return headingText(node.props.children);
  return "";
}
