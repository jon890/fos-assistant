/** 사용량 화면과 작업 과정 패널이 부르는 요청이다. 응답을 읽고 오류를 보이는 일은 화면이 맡는다. */

/** 그 실행에서 시작한 실행 트리를 읽는다. 없는 실행이면 404 를 준다. */
export function fetchExecutionTree(executionId: number): Promise<Response> {
  return fetch(`/api/usage/executions/${executionId}/tree`, {
    cache: "no-store",
  });
}

/** 그 달의 사용량을 고른 묶음별로 합쳐 읽는다. */
export function fetchUsageBreakdown(
  axis: string,
  month: string,
): Promise<Response> {
  return fetch(
    `/api/usage/breakdown?axis=${encodeURIComponent(axis)}&month=${encodeURIComponent(month)}`,
    { cache: "no-store" },
  );
}
