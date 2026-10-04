import { expect, setSession, test } from "./fixtures.ts";

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

/** 한국 달력의 날짜다. 집계가 `Asia/Seoul` 로 날짜를 끊는다. */
function dateInSeoul(at: number): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date(at));
}

test("화면에서 보낸 질문이 관리자 사용량의 첫 반응 시간 절에 오늘 줄로 보인다", async ({ page }) => {
  // 보내기 전에 날짜를 정해 둔다. 한국 자정을 넘기며 돌아도 그날이나 다음 날 가운데 한 줄에 잡힌다.
  // Asia/Seoul 에는 일광 절약 시간이 없어 24시간 뒤가 늘 다음 날이다.
  const sentAt = Date.now();
  const dates = [dateInSeoul(sentAt), dateInSeoul(sentAt + ONE_DAY_MS)];
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("첫 반응 시간 검사");
  await page.getByRole("button", { name: "보내기" }).click();
  // 스트리밍 경로의 답이 저장된 뒤에야 집계가 그 turn 을 센다. 답 메시지에 동작 줄이 생기면 끝난 것이다.
  const answer = page.getByTestId("assistant-message").last();
  await expect(answer.getByRole("button", { name: "답 다시 만들기" })).toBeVisible({ timeout: 30_000 });

  await page.goto("/admin/usage");
  const section = page.getByTestId("latency-section");
  await expect(section.getByRole("heading", { name: "첫 반응 시간", level: 2 })).toBeVisible();
  const row = section
    .locator(dates.map((date) => `[data-testid="latency-row"][data-date="${date}"]:visible`).join(", "))
    .first();
  await expect(row).toBeVisible();
  await expect(row).toContainText(/[1-9]\d*건/);
});

test("MEMBER 역할의 일반 사용량 화면에는 첫 반응 시간 절이 없다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  // 처음 오는 사용자는 첫 요청이 그를 만든다. 화면을 열기 전에 그 요청을 먼저 보낸다.
  const provision = await page.request.get("/api/agents");
  expect(provision.ok()).toBeTruthy();
  await page.goto("/usage");

  await expect(page.getByRole("heading", { name: "사용량", exact: true, level: 1 })).toBeVisible();
  await expect(page.getByTestId("latency-section")).toHaveCount(0);
});
