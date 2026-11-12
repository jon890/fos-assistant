import type { BindingStatus } from "@/lib/connection";

/**
 * 붙인 바인딩이 `PENDING` 에서 `READY` 로 바뀌기를 기다리며 다시 읽는 간격이다.
 *
 * <p>Control Plane 은 붙이기 응답을 늘 `PENDING` 으로 돌려주고, 반영 예정 시각(`assistant.connector.binding.apply-delay`)이
 * 지난 뒤 스스로 확인해 `READY` 로 바꾼다. 화면은 그 전환을 알 방법이 없어 다시 읽어야 한다.
 */
export const BINDING_POLL_MS = 10_000;

/**
 * 한 화면이 이어서 다시 읽는 횟수의 상한이다. 반영 예정 확인이 실패해 `PENDING` 으로 남은 바인딩은 관리자가 눌러야
 * 바뀌므로 끝없이 읽지 않는다. 반영 예정 시각까지의 기다림과 확인 주기를 넉넉히 덮는다.
 */
export const BINDING_POLL_LIMIT = 60;

/**
 * 사용자가 기다리면 저절로 쓸 수 있게 되는 바인딩인지 본다.
 *
 * <p>재시작 대기는 관리자가 반영 완료를 눌러야 해서 기다린다고 바뀌지 않으므로 뺀다.
 */
export function isAwaitingApply(view: {
  status: BindingStatus | null;
  restartRequired: boolean;
}): boolean {
  return view.status === "PENDING" && !view.restartRequired;
}
