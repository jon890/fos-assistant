export type ControlPlaneResult<T> =
  | { ok: true; status: number; data: T }
  | { ok: false; status: number; code: string; message: string };

const FALLBACK_CODE = "INTERNAL_ERROR";
const FALLBACK_MESSAGE = "요청을 처리하지 못했어요.";

/**
 * Control Plane 응답의 상태와 본문을 결과로 바꾼다.
 *
 * <p>본문이 JSON 이 아니면 앞단이 돌려준 오류 페이지일 수 있다. 원문은 화면에 싣지 않고, Control Plane 에
 * 닿지 못했을 때와 같은 코드와 문구로 알린다. 성공 상태인데 본문을 읽지 못하면 응답을 쓸 수 없으므로 502 로
 * 바꾸고, 오류 상태면 그 상태를 그대로 둔다. `callControlPlane` 이 부르고, `@/auth` 를 읽지 않아 단위
 * 검사가 따로 부를 수 있게 이 파일에 둔다.
 */
export function readControlPlaneResult<T>(status: number, text: string): ControlPlaneResult<T> {
  const succeeded = status >= 200 && status < 300;
  let payload: { code?: string; message?: string } | null = null;
  if (text.length > 0) {
    try {
      payload = JSON.parse(text);
    } catch {
      return { ok: false, status: succeeded ? 502 : status, code: FALLBACK_CODE, message: FALLBACK_MESSAGE };
    }
  }
  if (!succeeded) {
    return {
      ok: false,
      status,
      code: payload?.code ?? FALLBACK_CODE,
      message: payload?.message ?? FALLBACK_MESSAGE,
    };
  }
  return { ok: true, status, data: payload as T };
}
