/** 받침이 있으면 0 보다 큰 값이다. 한글 음절 밖의 글자는 null 이다. */
function hangulFinal(char: string): number | null {
  const code = char.charCodeAt(0) - 0xac00;
  if (code < 0 || code > 11171) return null;
  return code % 28;
}

/** 영어로 읽을 때 받침처럼 끝나는 글자다. 「Bot이」, 「Alice가」 */
const CLOSED_LATIN = /[bcdgjklmnpqrstvxz]$/i;
/** 한국어로 읽을 때 받침이 있는 숫자다. 영, 일, 삼, 육, 칠, 팔, 십 */
const CLOSED_DIGIT = /[013678]$/;

/**
 * 이름 뒤에 받침에 맞는 조사를 붙인다. `closed` 는 받침이 있을 때의 조사(「이」, 「과」)이고 `open` 은 없을 때(「가」, 「와」)다.
 */
export function withParticle(
  name: string,
  closed: string,
  open: string,
): string {
  const last = name.at(-1) ?? "";
  const final = hangulFinal(last);
  const isClosed =
    final !== null
      ? final > 0
      : CLOSED_LATIN.test(last) || CLOSED_DIGIT.test(last);
  return `${name}${isClosed ? closed : open}`;
}
