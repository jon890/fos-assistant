/**
 * 시나리오 파일이 함께 쓰는 것들이다.
 *
 * 러너가 Control Plane 과 fake Hermes 를 띄운 뒤 시나리오마다 {@link Context} 를 넘긴다.
 * 시나리오는 그 문맥으로만 바깥과 이야기하고 포트나 비밀값을 직접 알지 않는다.
 */
import { createHmac } from "node:crypto";
import type { FakeHermes } from "./fake-hermes.ts";

export type Context = {
  /** Control Plane 의 `/api/v1` 까지의 주소 */
  readonly api: string;
  /**
   * 가족 사용자의 토큰. 먼저 부른 쪽이 admin 이 된다.
   *
   * `signin` 은 로그인 판정 경로만 부르는 토큰이라 신원을 담지 않는다.
   */
  readonly tokens: {
    readonly dad: string;
    readonly kid: string;
    /** 화면에서 더해진 뒤 처음 들어오는 사람. 그 첫 요청에 사용자와 에이전트가 함께 생긴다 */
    readonly aunt: string;
    readonly signin: string;
  };
  /** fake Hermes 가 듣고 있는 주소 */
  readonly hermesBaseUrl: string;
  /** profile 마다 쓰는 API server key. 대역을 하나 더 띄우는 시나리오가 같은 값을 받아야 한다 */
  readonly hermesProfileKey: string;
  /** 실행 완료를 제어하는 fake Hermes */
  readonly hermes: FakeHermes;
};

export type Scenario = {
  readonly name: string;
  run(context: Context): Promise<void>;
};

export class ScenarioFailure extends Error {}

/** 시나리오 안의 단계를 한 줄로 알린다. */
export function step(message: string): void {
  console.log(`   - ${message}`);
}

export function fail(message: string): never {
  throw new ScenarioFailure(message);
}

export function expect(condition: boolean, message: string): void {
  if (!condition) fail(message);
}

/**
 * 웹이 발급하는 것과 같은 짧은 수명의 토큰을 만든다.
 *
 * <p>메일 주소가 같으면 몇 번을 불러도 같은 사용자가 된다. Control Plane 은 이 토큰의 서명만 보고
 * 누구인지 정하므로, 서명을 망가뜨린 토큰은 거절되어야 한다.
 */
export function mintToken(email: string, secret: string): string {
  return sign({ sub: email, name: email }, secret);
}

/**
 * 웹이 로그인 판정을 물을 때 쓰는 토큰을 만든다.
 *
 * <p>아직 아무 사용자도 없는 시점에 쓰는 토큰이라 신원을 담지 않는다. 누구를 묻는지는 요청 본문이
 * 적고, Control Plane 은 `purpose` 가 이 값인 것만 그 경로에서 받는다.
 */
export function mintSignInToken(secret: string): string {
  return sign({ purpose: "signin" }, secret);
}

function sign(claims: Record<string, unknown>, secret: string): string {
  const base64url = (raw: Buffer | string): string =>
    Buffer.from(raw).toString("base64url");

  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const now = Math.floor(Date.now() / 1000);
  const payload = base64url(JSON.stringify({ ...claims, iat: now, exp: now + 600 }));
  const signature = createHmac("sha256", secret)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${signature}`;
}

export type Response = {
  readonly status: number;
  readonly body: string;
  json<T>(): T;
};

type CallOptions = {
  readonly method?: string;
  readonly token?: string;
  readonly body?: unknown;
  readonly signal?: AbortSignal;
};

/** Control Plane 을 부른다. 상태 코드를 던지지 않고 그대로 돌려주므로 거절도 검사할 수 있다. */
export async function call(
  context: Context,
  path: string,
  options: CallOptions = {},
): Promise<Response> {
  const headers: Record<string, string> = {};
  if (options.token) headers.Authorization = `Bearer ${options.token}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  const response = await fetch(`${context.api}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  });
  const body = await response.text();
  return {
    status: response.status,
    body,
    json<T>(): T {
      if (body.length === 0) fail(`${path} 가 빈 본문을 돌려줬다`);
      return JSON.parse(body) as T;
    },
  };
}

/**
 * 파일 하나를 multipart 로 올린다. {@link call} 은 JSON 본문만 보내므로 따로 둔다.
 *
 * <p>상태 코드를 던지지 않고 그대로 돌려준다.
 */
export async function upload(
  context: Context,
  path: string,
  options: {
    readonly token: string;
    readonly field: string;
    readonly fileName: string;
    readonly contentType: string;
    readonly bytes: Uint8Array;
  },
): Promise<Response> {
  const form = new FormData();
  form.append(
    options.field,
    new Blob([options.bytes], { type: options.contentType }),
    options.fileName,
  );
  const response = await fetch(`${context.api}${path}`, {
    method: "POST",
    headers: { Authorization: `Bearer ${options.token}` },
    body: form,
  });
  const body = await response.text();
  return {
    status: response.status,
    body,
    json<T>(): T {
      if (body.length === 0) fail(`${path} 가 빈 본문을 돌려줬다`);
      return JSON.parse(body) as T;
    },
  };
}

/** 기대한 상태 코드가 아니면 받은 본문까지 붙여 세운다. */
export function expectStatus(
  response: Response,
  expected: number,
  what: string,
): Response {
  if (response.status !== expected) {
    fail(`${what}: HTTP ${expected} 을 기대했는데 ${response.status} 이 왔다\n${response.body}`);
  }
  return response;
}
