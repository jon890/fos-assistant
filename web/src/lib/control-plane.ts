import { SignJWT } from "jose";
import { auth } from "@/auth";

/** Control Plane 토큰은 한 요청 동안만 산다. 브라우저는 이 토큰을 보지 않는다. */
const TOKEN_LIFETIME = "2m";

function secret(): Uint8Array {
  const value = process.env.ASSISTANT_JWT_SECRET;
  if (!value) throw new Error("ASSISTANT_JWT_SECRET is not set");
  return new TextEncoder().encode(value);
}

function baseUrl(): string {
  const value = process.env.CONTROL_PLANE_BASE_URL;
  if (!value) throw new Error("CONTROL_PLANE_BASE_URL is not set");
  return value.replace(/\/$/, "");
}

export async function mintToken(email: string, name: string): Promise<string> {
  return new SignJWT({ name })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime(TOKEN_LIFETIME)
    .sign(secret());
}

/**
 * 로그인 판정만 물을 수 있는 토큰이다.
 *
 * <p>이 판정은 아직 아무 사용자도 없는 시점에 돌아서 신원을 담지 않는다. 누구를 묻는지는 요청 본문이
 * 적는다. Control Plane 은 `purpose` 가 이 값인 토큰만 그 경로에서 받는다.
 */
async function mintSignInToken(): Promise<string> {
  return new SignJWT({ purpose: "signin" })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setExpirationTime(TOKEN_LIFETIME)
    .sign(secret());
}

/**
 * 이 주소가 들어와도 되는지 Control Plane 에 묻는다.
 *
 * <p>허용 목록은 데이터베이스에 있고 실행 중에 바뀐다. 그래서 로그인마다 묻는다.
 *
 * <p>**답을 받지 못하면 거짓을 돌려준다.** 판정하지 못하는 동안 들여보내지 않는다. 이 경로는 사용자를
 * 만들지 않으므로, 거절된 주소가 `app_user` 를 남기지 않는다.
 */
export async function isSignInAllowed(email: string): Promise<boolean> {
  try {
    const response = await fetch(`${baseUrl()}/api/v1/signin/allowed`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${await mintSignInToken()}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email }),
      cache: "no-store",
    });
    if (!response.ok) return false;
    const payload = (await response.json()) as { allowed?: boolean };
    return payload.allowed === true;
  } catch {
    return false;
  }
}

export type ControlPlaneResult<T> =
  | { ok: true; status: number; data: T }
  | { ok: false; status: number; code: string; message: string };

export type ControlPlaneResponse =
  | { ok: true; response: Response }
  | { ok: false; status: number; code: string; message: string };

export async function requestControlPlane(
  path: string,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResponse> {
  const session = await auth();
  const email = session?.user?.email;
  if (!email) {
    return { ok: false, status: 401, code: "UNAUTHENTICATED", message: "로그인이 필요합니다." };
  }

  const token = await mintToken(email, session.user?.name ?? email);
  return {
    ok: true,
    response: await fetch(`${baseUrl()}${path}`, {
      method: init.method ?? "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        ...(init.body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      cache: "no-store",
    }),
  };
}

/**
 * 로그인한 가족 구성원으로서 Control Plane 을 부른다.
 *
 * <p>서버에서만 돈다. 부르는 사람이 누구인지는 세션이 정하고 요청 본문이 정하지 않는다. 그래서
 * 사용자가 본문을 고쳐 다른 사용자의 자료를 달라고 할 수 없다.
 */
export async function callControlPlane<T>(
  path: string,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResult<T>> {
  const opened = await requestControlPlane(path, init);
  if (!opened.ok) return opened;
  const response = opened.response;

  const text = await response.text();
  const payload = text.length > 0 ? JSON.parse(text) : null;
  if (!response.ok) {
    return {
      ok: false,
      status: response.status,
      code: payload?.code ?? "INTERNAL_ERROR",
      message: payload?.message ?? "요청을 처리하지 못했습니다.",
    };
  }
  return { ok: true, status: response.status, data: payload as T };
}
