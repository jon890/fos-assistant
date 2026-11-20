import type { BindingStatus } from "@/lib/connection";

/**
 * 붙인 바인딩이 `PENDING` 에서 `READY` 로 바뀌기를 기다리며 다시 읽는 간격이다.
 *
 * <p>Control Plane 은 붙이기 응답을 늘 `PENDING` 으로 돌려주고, 반영 예정 시각(`assistant.connector.binding.apply-delay`)이
 * 지난 뒤 스스로 확인해 `READY` 로 바꾼다. 화면은 그 전환을 알 방법이 없어 다시 읽어야 한다.
 */
export const BINDING_POLL_MS = 10_000;

/**
 * 한 화면이 이어서 다시 읽는 횟수의 상한이다(약 16분). 끝없이 읽지 않되, 껐다 켜기가 주인의 실행을 기다리는 10분과
 * 두 번의 반영 예정 시각(각 150초), 확인 주기를 덮는다.
 */
export const BINDING_POLL_LIMIT = 100;

/**
 * 화면이 몇 분 안에 바뀌기를 기다리며 다시 읽을 바인딩인지 본다.
 *
 * <p>재시작 대기는 대화가 없는 틈의 재시작을 기다려 몇 시간 걸릴 수 있어 다시 읽지 않고, 새로 고치거나 다시 들어올 때
 * 보인다. 자동 반영이 실패한 바인딩은 관리자가 다시 확인하기 전에는 바뀌지 않으므로 뺀다.
 */
export function isAwaitingApply(view: {
  status: BindingStatus | null;
  restartRequired: boolean;
  applyFailed?: boolean;
}): boolean {
  return (
    view.status === "PENDING" && !view.restartRequired && !view.applyFailed
  );
}
