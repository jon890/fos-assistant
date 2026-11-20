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

/**
 * 이 브라우저에 로그인 세션이 남아 있는지 Auth.js 의 세션 경로로 확인한다.
 *
 * <p>읽지 못하면 `null` 이다. bfcache 에서 돌아온 문서는 그때 다시 읽어, 모를 때 앞 사용자의 화면을 남기지 않는다.
 */
export async function hasBrowserSession(): Promise<boolean | null> {
  try {
    const response = await fetch("/api/auth/session", { cache: "no-store" });
    if (!response.ok) return null;
    const body = (await response.json()) as { user?: unknown } | null;
    return Boolean(body?.user);
  } catch {
    return null;
  }
}
