/**
 * `useId()` 값으로 mermaid 가 쓸 요소 id 를 만든다. mermaid 는 id 로 CSS 선택자를 만들어 `:` 같은 글자가 있으면 깨진다.
 */
export function mermaidRenderId(reactId: string): string {
  const clean = reactId.replace(/[^A-Za-z0-9]/g, "");
  return `mmd-${clean || "0"}`;
}
