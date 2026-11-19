/**
 * 답 글에서 Hermes 쪽 그림 파일 경로를 가린다.
 *
 * <p>Hermes 의 이미지 도구는 모델에게 호스트 경로를 돌려주고, 모델은 그것을 `MEDIA:<경로>` 나 경로 그대로 답에 적을 수 있다.
 * 저장된 답은 Control Plane 이 응답에서 빼지만(`MediaPathMask`), 흐르는 답은 조각으로 와서 화면이 그릴 때 뺀다.
 * 두 쪽의 규칙이 같아야 한다.
 */
// 태그는 낱말의 일부(`SOCIAL_MEDIA:`)가 아니고 뒤가 경로이거나 비었을 때만 잡는다. 캐시 경로는 웹 주소의 일부가 아닐 때만 잡는다.
const PATH =
  /[ \t]*(?:(?<![\w])MEDIA:(?:[~/][^\s`'"()<>]*|(?=\s|$))|(?<![\w/.:~-])~?(?:\/[^\s`'"()<>]*)?\/cache\/generated\/images\/[^\s`'"()<>]*)/g;

/** 경로를 뺀 글이다. 경로가 없으면 받은 글 그대로다. */
export function stripMediaPaths(text: string): string {
  if (!text.includes("MEDIA:") && !text.includes("/cache/generated/images/"))
    return text;
  return text
    .replace(PATH, "")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
