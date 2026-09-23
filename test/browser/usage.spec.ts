import { encode } from "../../web/node_modules/next-auth/jwt.js";
import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, test, SWITCH_AGENT_CODE } from "./fixtures.ts";
import { AUTH_SECRET, CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL, WEB_BASE_URL } from "./settings.ts";

const SESSION_COOKIE = "authjs.session-token";

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

test("화면 폭에 맞춰 실행 기록을 카드나 표로 보인다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "사용량 화면 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  await page.goto("/usage");

  const cards = page.getByTestId("execution-cards");
  const table = page.getByTestId("execution-table");
  if (testInfo.project.name === "mobile") {
    await expect(cards).toBeVisible();
    await expect(table).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  } else {
    await expect(table).toBeVisible();
    await expect(cards).toBeHidden();
  }
});

test("이번 달 합계와 가격을 찾지 못한 실행을 구분한다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "가격 없음 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const freeResponse = await page.request.post("/api/chat", {
    data: { text: "무료 모델 검사", agentCode: "browser" },
  });
  expect(freeResponse.ok()).toBeTruthy();
  await page.goto("/usage");

  await expect(page.getByText("API 로 돌렸다면", { exact: false })).toBeVisible();
  await expect(page.getByText("가격을 찾지 못한 실행", { exact: true })).toBeVisible();
  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  await expect(records.getByText("가격 없음").first()).toBeVisible();
  await expect(records.getByText("0.0000 USD").first()).toBeVisible();
});

test("돌고 있는 실행은 시간과 금액 없이 보이고 완료 뒤에 끝난다", async ({ page, hermes }, testInfo) => {
  await hermes.holdNextRun();
  const chat = page.request.post("/api/chat", {
    data: { text: "진행 중 실행 검사", agentCode: "browser" },
  });
  try {
    await hermes.waitForHeldRun();

    await page.goto("/usage");
    const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
    const running = records.getByText("도는 중", { exact: true });
    await expect(running).toBeVisible();
    const container = testInfo.project.name === "mobile"
      ? running.locator("xpath=ancestor::article")
      : running.locator("xpath=ancestor::tr");
    await expect(container.getByTestId("execution-duration")).toHaveText("");
    await expect(container.getByTestId("execution-cost")).toHaveText("");
  } finally {
    await hermes.releaseHeldRun();
  }
  expect((await chat).ok()).toBeTruthy();
});

test("고아 실행은 중간에 끊겼다고 보인다", async ({ page }, testInfo) => {
  const response = await page.request.post("/api/chat", {
    data: { text: "고아 실행 검사", agentCode: "browser" },
  });
  expect(response.ok()).toBeTruthy();
  const orphan = await page.request.post(
    `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/usage/last-execution/orphaned`,
    { headers: { Authorization: `Bearer ${await controlPlaneToken()}` } },
  );
  expect(orphan.ok()).toBeTruthy();
  await page.goto("/usage");

  const records = page.getByTestId(testInfo.project.name === "mobile" ? "execution-cards" : "execution-table");
  await expect(records.getByText("중간에 끊김", { exact: true }).first()).toBeVisible();
});

test("실행 기록이 없으면 빈 상태를 보인다", async ({ context, page }) => {
  const token = await encode({
    salt: SESSION_COOKIE,
    secret: AUTH_SECRET,
    token: { sub: "empty@example.com", email: "empty@example.com", name: "빈 사용자" },
  });
  await context.addCookies([{
    name: SESSION_COOKIE,
    value: token,
    url: WEB_BASE_URL,
    httpOnly: true,
    sameSite: "Lax",
  }]);

  const provision = await page.request.get("/api/agents");
  expect(provision.ok()).toBeTruthy();

  await page.goto("/usage");
  await expect(page.getByText("아직 실행 기록이 없다", { exact: true })).toBeVisible();
});

test("막혀서 넘어간 실패와 보통 실패를 다르게 보인다", async ({ page, hermes }, testInfo) => {
  const blockedProvider = `usage-blocked-${testInfo.project.name}`;
  await page.request.put(`/api/admin/agents/${SWITCH_AGENT_CODE}/models`, {
    data: {
      models: [
        { provider: blockedProvider, model: "blocked-model" },
        { provider: "openai-codex", model: "gpt-5.6-sol" },
      ],
    },
  });
  await hermes.blockProvider(blockedProvider);
  try {
    const response = await page.request.post("/api/chat", {
      data: { text: "사용량 넘김 검사", agentCode: SWITCH_AGENT_CODE },
    });
    expect(response.ok()).toBeTruthy();
  } finally {
    await hermes.clearBlockedProviders();
  }

  await page.goto("/usage");
  const records = page.getByTestId(
    testInfo.project.name === "mobile" ? "execution-cards" : "execution-table",
  );
  await expect(records.getByText("막혀서 다음 모델로 넘어감").first()).toBeVisible();
  await expect(records.getByTestId("execution-retry-of").first()).toBeVisible();
});

/**
 * 공유 gateway 의 동시 실행 한도에 닿아 거절당한 실행을 보통 실패와 다르게 보이는지 본다.
 *
 * <p>같은 문구로 보이면 한도를 올려야 하는지 이 화면으로 판단할 수 없다.
 */
test("붐벼서 거절된 실행은 다른 실패와 다르게 보인다", async ({ page, hermes }, testInfo) => {
  await hermes.busy();
  try {
    const response = await page.request.post("/api/chat", {
      data: { text: "붐빔 화면 검사", agentCode: "browser" },
    });
    expect(response.status()).toBe(429);
  } finally {
    await hermes.clearBusy();
  }

  await page.goto("/usage");
  const records = page.getByTestId(
    testInfo.project.name === "mobile" ? "execution-cards" : "execution-table",
  );
  await expect(records.getByText("붐벼서 거절됨", { exact: true }).first()).toBeVisible();
});
