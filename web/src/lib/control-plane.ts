import { SignJWT } from "jose";
import { redirect } from "next/navigation";
import { auth, signOut } from "@/auth";
import { isAccessRevoked, REVOKED_SIGN_OUT_PATH } from "@/lib/access-revoked";
import {
  readControlPlaneResult,
  type ControlPlaneResult,
} from "@/lib/control-plane-result";

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

export type ControlPlaneResponse =
  | { ok: true; response: Response }
  | { ok: false; status: number; code: string; message: string };

type Authorized =
  | { ok: true; token: string }
  | { ok: false; status: number; code: string; message: string };

/**
 * 세션에서 메일 주소를 꺼내 Control Plane 토큰을 만든다.
 *
 * <p>`requestControlPlane` 과 `forwardControlPlane` 이 이 확인과 토큰 발급을 같이 쓴다.
 */
async function authorize(): Promise<Authorized> {
  const session = await auth();
  const email = session?.user?.email;
  if (!email) {
    return {
      ok: false,
      status: 401,
      code: "UNAUTHENTICATED",
      message: "로그인이 필요해요.",
    };
  }
  return {
    ok: true,
    token: await mintToken(email, session.user?.name ?? email),
  };
}

/**
 * Next.js 가 화면을 그리는 중에 쿠키를 고치려 할 때 던지는 오류의 문구다.
 *
 * <p>그 오류 클래스는 Next.js 가 내보내지 않는다. 안쪽 경로에서 가져오면 서버 묶음에 든 것과 다른 사본이라
 * `instanceof` 가 맞지 않으므로 문구의 앞부분으로 알아본다.
 *
 * <p>next 를 올릴 때 이 문구가 그대로인지 다시 본다. 달라지면 `test/browser/access-revoked.spec.ts` 의 첫
 * 검사가 실패한다.
 */
const READONLY_COOKIES_MESSAGE =
  "Cookies can only be modified in a Server Action or Route Handler";

/**
 * Control Plane 이 꺼진 사용자라고 답하면 세션을 끊는다.
 *
 * <p>세션이 남으면 꺼진 사용자가 화면마다 오류만 보고 로그인 화면으로 가지 못한다. 401 전체가 아니라
 * `ACCESS_REVOKED` 일 때만 끊는다. 본문은 사본에서 읽으므로 부르는 쪽이 원래 응답을 그대로 읽을 수 있다.
 *
 * <p>Route Handler 와 Server Action 에서는 여기서 쿠키가 지워지고 401 이 그대로 나간다. `redirect` 하지
 * 않는 것은 브라우저의 `fetch` 가 로그인 화면 HTML 을 JSON 으로 읽게 되기 때문이다. 서버 컴포넌트는 쿠키를
 * 고치지 못하므로 쿠키를 지울 수 있는 라우트로 보낸다.
 */
async function endRevokedSession(response: Response): Promise<void> {
  if (response.status !== 401) return;
  if (!isAccessRevoked(response.status, await response.clone().text())) return;
  try {
    // redirectTo 가 없으면 next-auth 가 Referer 를 callbackUrl 로 쓴다.
    await signOut({ redirect: false, redirectTo: "/signin" });
  } catch (error) {
    // 쿠키를 고칠 수 없다는 오류일 때만 넘긴다. 모든 오류에 넘기면 그 라우트 안에서 실패했을 때 같은
    // 주소로 되돌아오는 루프가 된다.
    if (
      error instanceof Error &&
      error.message.startsWith(READONLY_COOKIES_MESSAGE)
    ) {
      redirect(REVOKED_SIGN_OUT_PATH);
    }
    throw error;
  }
}

export async function requestControlPlane(
  path: string,
  init: {
    method?: string;
    body?: unknown;
    /** 끊기면 Control Plane 으로 가는 요청도 끊는다. 끝나지 않는 스트림을 넘길 때 준다. */
    signal?: AbortSignal;
  } = {},
): Promise<ControlPlaneResponse> {
  const authorized = await authorize();
  if (!authorized.ok) return authorized;

  const response = await fetch(`${baseUrl()}${path}`, {
    method: init.method ?? "GET",
    headers: {
      Authorization: `Bearer ${authorized.token}`,
      ...(init.body === undefined
        ? {}
        : { "Content-Type": "application/json" }),
    },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
    cache: "no-store",
    signal: init.signal,
  });
  await endRevokedSession(response);
  return { ok: true, response };
}

/**
 * 로그인한 사용자로서 Control Plane 을 부르되, 본문을 손대지 않고 그대로 흘려보낸다.
 *
 * <p>multipart 업로드나 이미지 응답처럼 JSON 으로 다시 감싸면 안 되는 본문에 쓴다. `callControlPlane` 과
 * 달리 응답을 읽지 않고 `Response` 그대로 돌려주므로, 부르는 쪽이 스트림을 그대로 옮길 수 있다.
 */
export async function forwardControlPlane(
  path: string,
  init: {
    method: string;
    body?: ReadableStream<Uint8Array> | null;
    contentType?: string | null;
    /** 그대로 옮길 요청 머리글이다. `Authorization` 과 `Content-Type` 은 이름의 대소문자와 관계없이 이 함수가 정한 값이 이긴다. */
    headers?: Record<string, string>;
  },
): Promise<ControlPlaneResponse> {
  const authorized = await authorize();
  if (!authorized.ok) return authorized;

  // `Headers` 는 이름을 대소문자 없이 다루므로 `set` 이 `authorization` 처럼 다르게 쓴 옮길 머리글도 덮는다.
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${authorized.token}`);
  if (init.contentType) headers.set("Content-Type", init.contentType);

  // 본문이 스트림이면 Node 의 fetch 에 duplex 를 함께 줘야 한다. 없으면 요청이 거절된다. 표준
  // RequestInit 타입에는 아직 이 칸이 없어 따로 넓혀 쓴다.
  const requestInit: RequestInit & { duplex?: "half" } = {
    method: init.method,
    headers,
    body: init.body ?? undefined,
    cache: "no-store",
  };
  if (init.body) requestInit.duplex = "half";

  // 연결이 끊기면 fetch 가 던진다. 라우트가 JSON 이 아닌 500 을 내지 않게 오류 결과로 바꿔 돌려준다.
  let response: Response;
  try {
    response = await fetch(`${baseUrl()}${path}`, requestInit);
  } catch {
    return {
      ok: false,
      status: 502,
      code: "INTERNAL_ERROR",
      message: "요청을 처리하지 못했어요.",
    };
  }
  // 위 `try` 밖에서 부른다. 안에서 부르면 `redirect` 가 던진 것이 502 로 바뀐다.
  await endRevokedSession(response);
  return { ok: true, response };
}

/**
 * 로그인한 사용자로서 Control Plane 을 부른다.
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
  return readControlPlaneResult<T>(response.status, await response.text());
}
