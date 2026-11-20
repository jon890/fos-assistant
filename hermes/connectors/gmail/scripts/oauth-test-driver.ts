// OAuth 도구를 실제 HTTP 경계에서 검사한다. 실제 Google 계정과 토큰 서버는 쓰지 않는다.
import { callbackListener, exchangeCode, main } from "./get_refresh_token.ts";

const mode = process.argv[2];
if (mode === "callback") {
  const listener = await callbackListener("right");
  try {
    for (const query of [
      "state=wrong&code=ignored",
      "state=right&error=access_denied",
    ])
      await (await fetch(`${listener.redirectUri}?${query}`)).text();
    console.log(JSON.stringify(await listener.answer));
  } finally {
    await listener.close();
  }
} else if (mode === "exchange-error") {
  try {
    await exchangeCode(
      "https://token.invalid",
      "id",
      "secret",
      "code",
      "http://callback",
      "verifier",
      async () => new Response("secret-body", { status: 400 }),
    );
  } catch (error) {
    console.log((error as Error).message);
  }
} else if (mode === "timeout") {
  const listener = await callbackListener("state", 1);
  try {
    console.log(await listener.answer);
  } finally {
    await listener.close();
  }
} else if (mode === "main-success") {
  process.exitCode = await main({
    read: async (secret) => (secret ? "mock-secret" : "mock-client"),
    listen: async () => ({
      redirectUri: "http://127.0.0.1",
      answer: Promise.resolve({ code: "mock-code" }),
      close: async () => {},
    }),
    exchange: async () => "mock-refresh-token",
  });
} else {
  console.error(
    "사용법: node oauth-test-driver.ts callback|exchange-error|timeout|main-success",
  );
  process.exitCode = 2;
}
