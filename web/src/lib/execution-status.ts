/** 상태 배지가 쓰는 `Badge` 의 변형이다. */
export type ExecutionStatusVariant =
  "destructive" | "info" | "outline" | "success";

/**
 * 실행의 상태 배지 색을 고른다. 실행 기록과 실행 상세가 같은 규칙으로 그린다.
 *
 * <p>도는 중은 info, 실패는 destructive, 성공은 success 다. 취소됨과 모르는 상태는 흐린 표시로 둔다.
 * 상태가 실패가 아니어도 오류 코드가 있으면 실패로 그린다. 오류 코드를 주지 않는 쪽은 상태만으로 고른다.
 *
 * <p>단위 테스트가 `node --test` 로 직접 읽으므로 다른 모듈을 런타임에 import 하지 않는다.
 */
export function executionStatusVariant(execution: {
  status: string;
  errorCode?: string | null;
}): ExecutionStatusVariant {
  if (execution.status === "RUNNING") return "info";
  if (execution.status === "FAILED" || (execution.errorCode ?? null) !== null)
    return "destructive";
  if (execution.status === "CANCELLED") return "outline";
  if (execution.status === "SUCCEEDED") return "success";
  return "outline";
}

const RESTART_INTERRUPTED_CODES: ReadonlySet<string> = new Set([
  "ORPHANED",
  "REMOTE_RUN_LOST",
  "RECONCILE_TIMEOUT",
  "RECONCILE_UNREACHABLE",
]);

/** 기동 정리가 실패로 적을 때 쓰는 오류 코드인가. 사용량 화면이 「중간에 중단됨」 으로 보인다. */
export function isInterruptedByRestart(
  errorCode: string | null | undefined,
): boolean {
  return (
    errorCode !== null &&
    errorCode !== undefined &&
    RESTART_INTERRUPTED_CODES.has(errorCode)
  );
}
