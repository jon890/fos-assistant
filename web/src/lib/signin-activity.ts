import { SignJWT } from "jose";

const SIGN_IN_TOKEN_LIFETIME = "2m";
const REQUEST_TIMEOUT_MS = 5_000;

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

/** 웹 세션이 만들어진 뒤 로그인 완료 시각을 Control Plane 에 기록한다. */
export async function recordSignIn(email: string): Promise<void> {
  const token = await new SignJWT({ purpose: "signin" })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setExpirationTime(SIGN_IN_TOKEN_LIFETIME)
    .sign(secret());
  const response = await fetch(`${baseUrl()}/api/v1/signin/completed`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ email }),
    cache: "no-store",
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new Error(`sign-in activity request failed: ${response.status}`);
  }
}
