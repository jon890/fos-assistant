/** 보고를 열었다고 남긴 뒤 점검 대화로 간다. */
export function openProactiveReport(checkId: number): Promise<Response> {
  return fetch(`/api/proactive-checks/${checkId}/report/open`, {
    method: "POST",
  });
}
