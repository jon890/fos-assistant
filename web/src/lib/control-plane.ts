import { SignJWT } from "jose";
import { auth } from "@/auth";

/** The Control Plane token lives for one request. The browser never sees it. */
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

async function mintToken(email: string, name: string): Promise<string> {
  return new SignJWT({ name })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime(TOKEN_LIFETIME)
    .sign(secret());
}

export type ControlPlaneResult<T> =
  | { ok: true; status: number; data: T }
  | { ok: false; status: number; code: string; message: string };

/**
 * Calls the Control Plane as the signed-in family member.
 *
 * <p>Runs only on the server: the caller's identity comes from the session, never from the request
 * body, so a member cannot ask for another member's data by editing a payload.
 */
export async function callControlPlane<T>(
  path: string,
  init: { method?: string; body?: unknown } = {},
): Promise<ControlPlaneResult<T>> {
  const session = await auth();
  const email = session?.user?.email;
  if (!email) {
    return { ok: false, status: 401, code: "UNAUTHENTICATED", message: "로그인이 필요합니다." };
  }

  const token = await mintToken(email, session.user?.name ?? email);
  const response = await fetch(`${baseUrl()}${path}`, {
    method: init.method ?? "GET",
    headers: {
      Authorization: `Bearer ${token}`,
      ...(init.body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    cache: "no-store",
  });

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
