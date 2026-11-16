/**
 * 화면 위 잠금 띠의 상태다. 판정은 `docs/features/browser-cowork/design.md` 의 「PR3 사람 잠금」 의 띠 표가 갖는다.
 * `human` 은 「직접 하는 중」, `approved` 는 「승인한 작업을 마무리하는 중」, `agent` 는 「에이전트가 쓰는 중」 이다.
 */
export type HoldBand = "human" | "approved" | "agent" | null;

/**
 * 띠를 고른다. 사람이 쥐었으면 어느 탭을 보든 `human` 이고, 아니면 지금 탭이 에이전트가 쥔 탭일 때만 띠가 있다.
 *
 * @param watchingAgentTab 지금 보는 탭을 에이전트가 쥐었는가
 */
export function holdBand(
  hold: { human: boolean; approved: boolean },
  watchingAgentTab: boolean,
): HoldBand {
  if (hold.human) return "human";
  if (!watchingAgentTab) return null;
  return hold.approved ? "approved" : "agent";
}
