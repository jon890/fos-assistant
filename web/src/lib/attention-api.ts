/** 지금 화면 바깥에서 부르는 요청이다. 응답을 읽고 실패를 다루는 일은 부르는 쪽이 맡는다. */

/** 지금 볼 것의 건수(`nowCount`)를 읽는다. */
export function fetchAttentionSummary(): Promise<Response> {
  return fetch("/api/attention/summary", { cache: "no-store" });
}
