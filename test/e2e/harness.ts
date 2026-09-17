/**
 * 시나리오 파일이 함께 쓰는 것들이다.
 *
 * 러너가 Control Plane 과 fake Hermes 를 띄운 뒤 시나리오마다 {@link Context} 를 넘긴다.
 * 시나리오는 그 문맥으로만 바깥과 이야기하고 포트나 비밀값을 직접 알지 않는다.
 */
import { createHmac } from "node:crypto";

export type Context = {
  /** Control Plane 의 `/api/v1` 까지의 주소 */
  readonly api: string;
  /** 가족 구성원의 토큰. 먼저 부른 쪽이 admin 이 된다 */
  readonly tokens: { readonly dad: string; readonly kid: string };
  /** fake Hermes 가 듣고 있는 주소 */
  readonly hermesBaseUrl: string;
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
  const base64url = (raw: Buffer | string): string =>
    Buffer.from(raw).toString("base64url");

  const header = base64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const now = Math.floor(Date.now() / 1000);
  const payload = base64url(
    JSON.stringify({ sub: email, name: email, iat: now, exp: now + 600 }),
  );
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
