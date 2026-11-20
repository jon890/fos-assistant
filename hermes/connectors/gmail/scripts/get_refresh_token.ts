// 로컬 임시 HTTP callback과 PKCE로 refresh token을 받는다. 토큰만 표준 출력에 쓴다.
import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { createInterface } from "node:readline";
import { Writable } from "node:stream";
import { pathToFileURL } from "node:url";

export const WAIT_SECONDS = 300;
const TOKEN_URL = "https://oauth2.googleapis.com/token";
export class ExchangeError extends Error {}
export function codeChallenge(verifier: string): string {
  return createHash("sha256").update(verifier, "ascii").digest("base64url");
}
export function authorizationUrl(
  clientId: string,
  redirectUri: string,
  state: string,
  challenge: string,
): string {
  return (
    "https://accounts.google.com/o/oauth2/v2/auth?" +
    new URLSearchParams({
      client_id: clientId,
      redirect_uri: redirectUri,
      response_type: "code",
      scope:
        "https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/gmail.settings.basic",
      access_type: "offline",
      prompt: "consent",
      state,
      code_challenge: challenge,
      code_challenge_method: "S256",
    })
  );
}
export async function exchangeCode(
  tokenUrl: string,
  clientId: string,
  clientSecret: string,
  code: string,
  redirectUri: string,
  verifier: string,
  request: (url: string, init: RequestInit) => Promise<Response> = fetch,
): Promise<string | null> {
  let answer: unknown;
  try {
    const response = await request(tokenUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json",
      },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: redirectUri,
        code_verifier: verifier,
      }),
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) {
      await response.body?.cancel();
      throw new ExchangeError(
        `토큰 요청이 HTTP ${response.status} 로 거절됐다`,
      );
    }
    answer = await response.json();
  } catch (error) {
    if (error instanceof ExchangeError) throw error;
    throw new ExchangeError(
      "토큰 endpoint 에 닿지 못했거나 응답을 읽지 못했다",
    );
  }
  const token =
    answer && typeof answer === "object" && "refresh_token" in answer
      ? answer.refresh_token
      : null;
  return typeof token === "string" && token ? token : null;
}

export async function callbackListener(
  state: string,
  waitMs = WAIT_SECONDS * 1000,
) {
  let complete: (value: Record<string, string> | null) => void;
  const answer = new Promise<Record<string, string> | null>((resolve) => {
    complete = resolve;
  });
  const server = createServer((request, response) => {
    const query = new URL(request.url!, "http://127.0.0.1").searchParams;
    const accepted =
      query.get("state") === state &&
      !!(query.get("code") || query.get("error"));
    const granted = accepted && !!query.get("code");
    const text = granted
      ? "끝났습니다. 이 창을 닫고 터미널로 돌아가세요."
      : "권한을 받지 못했습니다. 터미널을 확인하세요.";
    const body = `<!doctype html><meta charset="utf-8"><p>${text}</p>`;
    response.writeHead(granted ? 200 : 400, {
      "Content-Type": "text/html; charset=utf-8",
      "Content-Length": Buffer.byteLength(body),
      Connection: "close",
    });
    response.end(body);
    if (accepted) {
      const values: Record<string, string> = {};
      // Python parse_qs와 같이 첫 값만 쓴다. 빈 값은 기록하지 않는다.
      for (const [key, value] of query)
        if (value && !(key in values)) values[key] = value;
      complete(values);
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 15_000;
  server.setTimeout(15_000, (socket) => socket.destroy());
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.removeListener("error", reject);
      resolve();
    });
  });
  const address = server.address();
  if (!address || typeof address === "string")
    throw new Error("callback 주소를 구하지 못했다");
  const timer = setTimeout(() => complete(null), waitMs);
  return {
    redirectUri: `http://127.0.0.1:${address.port}`,
    answer,
    async close() {
      clearTimeout(timer);
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

type MainOptions = {
  read: (secret: boolean) => Promise<string>;
  say?: (text: string) => void;
  write?: (text: string) => void;
  listen?: typeof callbackListener;
  exchange?: typeof exchangeCode;
};
export async function main(options: MainOptions): Promise<number> {
  const say = options.say ?? ((text) => console.error(text));
  say("OAuth 클라이언트 ID: ");
  const clientId = (await options.read(false)).trim();
  say("OAuth 클라이언트 secret(입력이 보이지 않습니다): ");
  const clientSecret = (await options.read(true)).trim();
  if (!clientId || !clientSecret) {
    say("클라이언트 ID 와 secret 이 모두 필요합니다.");
    return 1;
  }
  const state = randomBytes(32).toString("base64url");
  const verifier = randomBytes(64).toString("base64url");
  const listener = await (options.listen ?? callbackListener)(state);
  let callback: Record<string, string> | null;
  try {
    say("");
    say("아래 주소를 브라우저로 열고 연결할 Google 계정으로 로그인하세요.");
    say("");
    say(
      authorizationUrl(
        clientId,
        listener.redirectUri,
        state,
        codeChallenge(verifier),
      ),
    );
    say("");
    say(
      `권한을 허용할 때까지 ${WAIT_SECONDS}초 동안 기다립니다. 그만두려면 Ctrl-C 를 누르세요.`,
    );
    callback = await listener.answer;
  } finally {
    await listener.close();
  }
  if (callback === null) {
    say(
      `${WAIT_SECONDS}초 안에 권한을 받지 못했습니다. 스크립트를 다시 돌리세요.`,
    );
    return 1;
  }
  if (!("code" in callback)) {
    say("권한을 받지 못했습니다. 동의 화면에서 허용했는지 확인하세요.");
    return 1;
  }
  let token: string | null;
  try {
    token = await (options.exchange ?? exchangeCode)(
      TOKEN_URL,
      clientId,
      clientSecret,
      callback.code,
      listener.redirectUri,
      verifier,
    );
  } catch (error) {
    if (!(error instanceof ExchangeError)) throw error;
    say(`토큰을 받지 못했습니다: ${error.message}`);
    return 1;
  }
  if (token === null) {
    say("응답에 refresh token 이 없습니다.");
    say(
      "이미 동의한 계정이면 Google 계정의 「서드 파티 앱 및 서비스」 에서 이 앱의 접근을 지우고 다시 하세요.",
    );
    return 1;
  }
  say("");
  say(
    "refresh token 입니다. 외부 서비스 연결에 붙여 넣은 뒤 터미널 기록에서 지우세요.",
  );
  (options.write ?? ((text) => console.log(text)))(token);
  return 0;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  if (process.argv.includes("--help") || process.argv.includes("-h")) {
    console.log(
      "사용법: node hermes/connectors/gmail/scripts/get_refresh_token.ts\nOAuth 클라이언트 ID와 secret을 입력하고 브라우저에서 권한을 허용한다. refresh token만 표준 출력에 쓴다.",
    );
  } else {
    let muted = false;
    const output = new Writable({
      write(chunk, encoding, done) {
        if (!muted) process.stderr.write(chunk, encoding);
        done();
      },
    });
    const reader = createInterface({
      input: process.stdin,
      output,
      terminal: !!process.stdin.isTTY,
    });
    const lines = reader[Symbol.asyncIterator]();
    reader.on("SIGINT", () => {
      reader.close();
      process.exit(1);
    });
    try {
      process.exitCode = await main({
        read: async (secret) => {
          muted = secret;
          return (await lines.next()).value ?? "";
        },
      });
    } finally {
      reader.close();
    }
  }
}
