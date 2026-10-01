import { expect, setSession, test } from "./fixtures.ts";

test("Memory 화면에서 개인 항목을 만들고 고치고 지운다", async ({ page }, testInfo) => {
  const title = `${testInfo.project.name} 음식 선호`;
  await page.goto("/memory");
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "그룹이 함께 아는 것" })).toBeVisible();
  await expect(page.getByRole("button", { name: "저장" })).toBeDisabled();

  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill(title);
  await page.getByLabel("내용").fill("국수는 맵지 않게 먹는다");
  await page.getByLabel("답을 만들 때 항상 함께 넣기").check();
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByText(title, { exact: true })).toBeVisible();

  const item = page.getByRole("heading", { name: title }).locator("xpath=ancestor::article");
  await item.getByRole("button", { name: "고치기" }).click();
  await page.getByRole("textbox").last().fill("국수는 맵지 않게 먹는다.");
  await page.getByRole("button", { name: "저장" }).last().click();
  await expect(page.getByText("국수는 맵지 않게 먹는다.", { exact: true })).toBeVisible();
  await item.getByRole("button", { name: "지우기" }).click();
  await expect(page.getByText(title, { exact: true })).toHaveCount(0);

  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth!);
});

test("관리자에게만 그룹 공용 범위를 보인다", async ({ page }) => {
  await page.goto("/memory");
  await expect(page.getByRole("option", { name: "그룹 공용" })).toHaveCount(1);
});

test("MEMBER 역할은 그룹 공용 Memory의 범위와 편집 제어를 보지 않는다", async ({ context, page }) => {
  const title = "MEMBER 편집 금지";
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("GROUP");
  await page.getByLabel("제목", { exact: true }).fill(title);
  await page.getByLabel("내용").fill("그룹만 아는 내용");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();

  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto("/memory");
  await expect(page.getByRole("option", { name: "그룹 공용" })).toHaveCount(0);
  const item = page.getByRole("heading", { name: title }).locator("xpath=ancestor::article");
  await expect(item.getByRole("button", { name: "고치기" })).toHaveCount(0);
  await expect(item.getByRole("button", { name: "지우기" })).toHaveCount(0);
});

test("제안을 처리하면 목록과 머리의 미처리 수가 함께 갱신된다", async ({ page }) => {
  const proposed = [{ id: 910, scope: "USER", ownerUserId: 1, title: "제안", content: "남길 사실", alwaysInject: false, status: "PROPOSED" }];
  let state: unknown[] = proposed;
  await page.route("**/api/memories", async (route) => {
    if (route.request().method() === "GET") return route.fulfill({ json: state });
    return route.fulfill({ status: 201, json: proposed[0] });
  });
  await page.route("**/api/memories/910/accept", async (route) => { state = [{ ...proposed[0], status: "ACCEPTED" }]; await route.fulfill({ json: state[0] }); });
  await page.route("**/api/memories/910/reject", async (route) => { state = []; await route.fulfill({ json: proposed[0] }); });
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill("새 항목");
  await page.getByLabel("내용").fill("내용");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toBeVisible();
  await expect(page.getByTestId("memory-proposal-count")).toHaveText("1");
  await page.getByRole("button", { name: "받아들이기" }).click();
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toHaveCount(0);
  await expect(page.getByTestId("memory-proposal-count")).toHaveCount(0);
});

test("제안을 물리면 제안 절과 머리의 미처리 수가 사라진다", async ({ page }) => {
  const proposed = [{ id: 911, scope: "USER", ownerUserId: 1, title: "거절할 제안", content: "남길 사실", alwaysInject: false, status: "PROPOSED" }];
  let state: unknown[] = proposed;
  await page.route("**/api/memories", async (route) => route.request().method() === "GET"
    ? route.fulfill({ json: state }) : route.fulfill({ status: 201, json: proposed[0] }));
  await page.route("**/api/memories/911/reject", async (route) => { state = []; await route.fulfill({ json: proposed[0] }); });
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill("새 항목");
  await page.getByLabel("내용").fill("내용");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByTestId("memory-proposal-count")).toHaveText("1");
  await page.getByRole("button", { name: "거절" }).click();
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toHaveCount(0);
  await expect(page.getByTestId("memory-proposal-count")).toHaveCount(0);
});

test("Memory 변경이 실패하면 성공처럼 닫지 않고 오류를 보인다", async ({ page }, testInfo) => {
  // 두 폭이 같은 제목을 만들고 지우지 않으면 같은 제목의 줄이 둘이 된다.
  const title = `실패 검사 ${testInfo.project.name} ${Date.now()}`;
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill(title);
  await page.getByLabel("내용").fill("원래 내용");
  await page.getByRole("button", { name: "저장" }).click();
  const item = page.getByRole("heading", { name: title, exact: true }).locator("xpath=ancestor::article");
  await page.route("**/api/memories/*", async (route) => route.fulfill({ status: 500 }));
  await item.getByRole("button", { name: "고치기" }).click();
  await item.getByRole("textbox").fill("바뀐 내용");
  await item.getByRole("button", { name: "저장" }).click();
  await expect(item.getByRole("alert")).toHaveText("기억을 고치지 못했어요.");
  await expect(item.getByRole("textbox")).toBeVisible();
});

test("문맥에 실리지 않은 항목에 표시가 보인다", async ({ page }) => {
  const list = [
    { id: 920, scope: "USER", ownerUserId: 1, title: "너무 긴 항목", content: "가".repeat(200), alwaysInject: true, status: "ACCEPTED", omittedFromContext: true },
    { id: 921, scope: "USER", ownerUserId: 1, title: "실린 항목", content: "짧게 남긴 사실", alwaysInject: true, status: "ACCEPTED", omittedFromContext: false },
  ];
  await page.route("**/api/memories", async (route) => route.request().method() === "GET"
    ? route.fulfill({ json: list }) : route.fulfill({ status: 201, json: list[0] }));
  await page.goto("/memory");
  await page.getByLabel("범위").selectOption("USER");
  await page.getByLabel("제목", { exact: true }).fill("새 항목");
  await page.getByLabel("내용").fill("내용");
  await page.getByRole("button", { name: "저장" }).click();

  const omitted = page.getByRole("heading", { name: "너무 긴 항목" }).locator("xpath=ancestor::article");
  await expect(omitted.getByTestId("memory-omitted")).toHaveText("길어서 답에 포함되지 않음");
  const kept = page.getByRole("heading", { name: "실린 항목" }).locator("xpath=ancestor::article");
  await expect(kept.getByTestId("memory-omitted")).toHaveCount(0);
});
