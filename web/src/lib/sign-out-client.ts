/**
 * 이 브라우저의 로그인을 푼다. `app/signout/route.ts` 가 세션 쿠키를 지우면 참이다.
 *
 * <p>응답이 204 가 아니거나 요청이 끊기면 거짓이다. 그때 로그인은 그대로다.
 */
export async function requestSignOut(): Promise<boolean> {
  try {
    const response = await fetch("/signout", { method: "POST" });
    return response.status === 204;
  } catch {
    return false;
  }
}
