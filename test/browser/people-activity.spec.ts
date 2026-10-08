import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import { expect, setSession, test } from "./fixtures.ts";
import { isolatedUser } from "./helpers.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

async function signInCompleted(email: string): Promise<void> {
  const token = await new SignJWT({ purpose: "signin" })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  const response = await fetch(
    `${CONTROL_PLANE_BASE_URL}/api/v1/signin/completed`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email }),
    },
  );
  expect(
    response.status,
    `로그인 완료 기록 실패: ${await response.text()}`,
  ).toBe(204);
}

test("관리자는 로그인과 마지막 대화 시각을 목록과 상세에서 본다", async ({
  context,
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "activity");
  const profile = `activity-${user.email.slice(0, user.email.indexOf("@")).replace("activity-", "")}`;
  const added = await page.request.post("/api/admin/people", {
    data: {
      email: user.email,
      displayName: "활동 검사 사용자",
      hermesProfile: profile,
    },
  });
  expect(
    added.ok(),
    `사용자를 만들지 못했다: ${added.status()} ${await added.text()}`,
  ).toBeTruthy();

  await signInCompleted(user.email);
  await setSession(context, user);
  const agents = await page.request.get("/api/agents");
  expect(
    agents.ok(),
    `기본 에이전트를 읽지 못했다: ${agents.status()} ${await agents.text()}`,
  ).toBeTruthy();
  const agent = ((await agents.json()) as Array<{ code?: string; ownedByMe: boolean }>).find((entry) => entry.ownedByMe);
  expect(agent?.code, "기본 에이전트 번호가 없다").toBeTruthy();
  try {
  const message = await page.request.post("/api/chat", {
    data: { text: "활동 시각 검사", agentCode: agent!.code },
  });
  expect(
    message.ok(),
    `사용자 메시지를 만들지 못했다: ${message.status()} ${await message.text()}`,
  ).toBeTruthy();

  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  const people = await page.request.get("/api/admin/people");
  expect(
    people.ok(),
    `관리자 목록을 읽지 못했다: ${people.status()}`,
  ).toBeTruthy();
  const activity = (
    (await people.json()) as Array<{
      email: string;
      lastLoginAt: string | null;
      lastConversationAt: string | null;
    }>
  ).find((person) => person.email === user.email);
  expect(activity?.lastLoginAt).toBeTruthy();
  expect(activity?.lastConversationAt).toBeTruthy();
  await page.goto("/admin/people");
  const row = page.getByRole("row").filter({ hasText: user.email });
  const login = row.locator("time").first();
  const conversation = row.locator("time").nth(1);
  await expect(login).toHaveAttribute("datetime", activity!.lastLoginAt!);
  await expect(conversation).toHaveAttribute(
    "datetime",
    activity!.lastConversationAt!,
  );
  await expect(login).toContainText("서울 시각");
  await expect(conversation).toContainText("서울 시각");
  await row.getByRole("button", { name: "상세" }).click();
  await expect(row.getByRole("button", { name: "상세" })).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  await expect(page.locator('[id^="person-detail-"]')).toBeVisible();
  } finally {
    await setSession(context, user);
    const deleted = await page.request.delete(`/api/agents/${agent!.code}`);
    expect(deleted.status(), "검사가 만든 기본 에이전트를 지우지 못했다").toBe(204);
  }
});

test("활동 기록이 없는 사용자는 목록과 상세에서 기록 없음을 본다", async ({
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "no-activity");
  const profile = `empty-${user.email.slice(0, user.email.indexOf("@")).replace("no-activity-", "")}`;
  const added = await page.request.post("/api/admin/people", {
    data: {
      email: user.email,
      displayName: "기록 없는 사용자",
      hermesProfile: profile,
    },
  });
  expect(
    added.ok(),
    `사용자를 만들지 못했다: ${added.status()} ${await added.text()}`,
  ).toBeTruthy();
  await page.goto("/admin/people");
  const row = page.getByRole("row").filter({ hasText: user.email });
  await expect(row.getByText("기록 없음", { exact: true })).toHaveCount(2);
  await row.getByRole("button", { name: "상세" }).click();
  const detailId = await row
    .getByRole("button", { name: "상세" })
    .getAttribute("aria-controls");
  const detail = page.locator(`#${detailId}`);
  await expect(
    detail.getByText("첫 로그인 여부", { exact: true }),
  ).toBeVisible();
  await expect(detail.getByText("기록 없음", { exact: true })).toHaveCount(2);
});

test("로그인 완료만 기록되어도 상세는 로그인 이력을 보인다", async ({
  page,
}, testInfo) => {
  const user = isolatedUser(testInfo, "signin-only");
  const profile = `signin-${user.email.slice(0, user.email.indexOf("@")).replace("signin-only-", "")}`;
  const added = await page.request.post("/api/admin/people", {
    data: {
      email: user.email,
      displayName: "로그인 기록 사용자",
      hermesProfile: profile,
    },
  });
  expect(
    added.ok(),
    `사용자를 만들지 못했다: ${added.status()} ${await added.text()}`,
  ).toBeTruthy();
  await signInCompleted(user.email);
  await page.goto("/admin/people");
  const row = page.getByRole("row").filter({ hasText: user.email });
  await row.getByRole("button", { name: "상세" }).click();
  const detailId = await row
    .getByRole("button", { name: "상세" })
    .getAttribute("aria-controls");
  const detail = page.locator(`#${detailId}`);
  await expect(
    detail.getByText("로그인한 적 있음", { exact: true }),
  ).toBeVisible();
  await expect(detail.getByText("기록 없음", { exact: true })).toHaveCount(1);
});
