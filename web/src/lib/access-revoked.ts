/** Control Plane 이 꺼진 사용자의 요청에 401 과 함께 싣는 오류 코드다. */
export const ACCESS_REVOKED = "ACCESS_REVOKED";

/** 서버 컴포넌트가 꺼진 사용자를 넘기는 라우트다. 그 라우트가 세션 쿠키를 지운다. */
export const REVOKED_SIGN_OUT_PATH = "/signout/revoked";

/**
 * 401 응답의 본문이 꺼진 사용자라고 말하는가. JSON 이 아니거나 code 가 다르면 거짓이다.
 *
 * <p>401 전체를 꺼진 사용자로 보지 않는다. 세션이 없을 때의 401 은 로그아웃할 세션이 없고, 앞단이 돌려준
 * 오류 페이지는 사용자가 꺼졌다는 근거가 아니다. `@/auth` 를 읽지 않아 단위 검사가 따로 부를 수 있게 이
 * 파일에 둔다.
 */
export function isAccessRevoked(status: number, text: string): boolean {
  if (status !== 401) return false;
  try {
    const payload: unknown = JSON.parse(text);
    return (
      typeof payload === "object" &&
      payload !== null &&
      (payload as { code?: unknown }).code === ACCESS_REVOKED
    );
  } catch {
    return false;
  }
}
