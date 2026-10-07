/** 기억을 누가 남겼는지 목록에 보이는 문구를 만든다. 칸의 뜻은 backend 의 `MemorySource` 가 갖는다. */
export type MemorySourceFields = {
  /** 에이전트가 남긴 기억이면 남긴 실행의 번호다. 사람이 직접 만들었으면 비어 있다. */
  proposedByExecutionId?: number | null;
  /** 남긴 에이전트의 이름이다. 읽는 사용자가 볼 수 있는 에이전트일 때만 온다. */
  sourceAgentName?: string | null;
  sourceAgentDeleted?: boolean;
};

export function isAgentMemory(memory: MemorySourceFields): boolean {
  return memory.proposedByExecutionId != null;
}

export function memorySourceLabel(memory: MemorySourceFields): string {
  if (!isAgentMemory(memory)) return "직접 남김";
  if (memory.sourceAgentDeleted) return "지운 에이전트가 남김";
  const name = memory.sourceAgentName?.trim();
  if (!name) return "에이전트가 남김";
  return `${withSubjectParticle(name)} 남김`;
}

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

/** 이름 뒤에 주격 조사 「이」 나 「가」 를 붙인다. */
export function withSubjectParticle(name: string): string {
  const last = name.at(-1) ?? "";
  const final = hangulFinal(last);
  const closed =
    final !== null
      ? final > 0
      : CLOSED_LATIN.test(last) || CLOSED_DIGIT.test(last);
  return `${name}${closed ? "이" : "가"}`;
}
