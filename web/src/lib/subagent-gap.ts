/** 금액을 합계에 넣지 못한 하위 에이전트 수다. 셋은 서로 겹치지 않는다. */
export type SubagentGap = {
  pending: number;
  unconfirmed: number;
  unpriced: number;
};

export function subagentGapTotal(gap: SubagentGap): number {
  return gap.pending + gap.unconfirmed + gap.unpriced;
}

/** 0 이 아닌 것만 「확인 중 2건, 확인 실패 1건, 가격 미확인 3건」 순서로 이어 붙인다. */
export function subagentGapDetail(gap: SubagentGap): string {
  const parts: string[] = [];
  if (gap.pending > 0)
    parts.push(`확인 중 ${gap.pending.toLocaleString("ko-KR")}건`);
  if (gap.unconfirmed > 0)
    parts.push(`확인 실패 ${gap.unconfirmed.toLocaleString("ko-KR")}건`);
  if (gap.unpriced > 0)
    parts.push(`가격 미확인 ${gap.unpriced.toLocaleString("ko-KR")}건`);
  return parts.join(", ");
}
