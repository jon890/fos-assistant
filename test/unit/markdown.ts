/** 문서 시험들이 함께 쓰는 Markdown 읽기 함수다. 시험 파일이 아니라 `node --test` 의 대상이 아니다. */

/** 헤딩과 절 이름을 같은 모양으로 맞춘다. `{@code X}` 는 `X` 로 바꾸고 백틱과 앞뒤 공백을 지운다. */
export function normalizeHeading(text: string): string {
  return text
    .replace(/\{@code ([^}]*)\}/g, "$1")
    .replaceAll("`", "")
    .trim();
}

/** 코드 펜스 안의 줄을 빈 줄로 바꾼다. 줄 번호는 그대로다. */
export function blankFences(markdown: string): string[] {
  let inFence = false;
  return markdown.split("\n").map((line) => {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      return "";
    }
    return inFence ? "" : line;
  });
}

/** 코드 펜스 밖의 `#` 부터 `######` 까지 모든 헤딩의 글을 나온 순서대로 모은다. */
export function headingTexts(markdown: string): string[] {
  const headings: string[] = [];
  for (const line of blankFences(markdown)) {
    const match = /^#{1,6}\s+(.*)$/.exec(line);
    if (match) headings.push(match[1].trim());
  }
  return headings;
}

/** 절로 가리킬 수 있는 이름이다. 헤딩과, ADR 이 절처럼 쓰는 `- **감당할 것**:` 같은 굵은 항목 이름이다. */
export function sectionNames(markdown: string): Set<string> {
  const names = headingTexts(markdown).map(normalizeHeading);
  for (const line of blankFences(markdown)) {
    const label = /^\s*(?:[-*] )?\*\*([^*]+)\*\*/.exec(line);
    if (label) names.push(normalizeHeading(label[1].replace(/[:.]$/, "")));
  }
  return new Set(names);
}
