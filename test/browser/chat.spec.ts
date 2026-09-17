import { expect, test } from "./fixtures.ts";

test("mobile에서 입력창을 유지하고 대화 목록을 서랍으로 쓴다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile");
  await page.goto("/");

  for (let index = 1; index <= 10; index += 1) {
    const response = await page.request.post("/api/chat", {
      data: { text: `모바일 대화 ${index}`, agentCode: "browser" },
    });
    expect(response.ok()).toBeTruthy();
  }
  await page.reload();

  const composer = page.getByPlaceholder("무엇을 도와줄까요");
  const box = await composer.boundingBox();
  expect(box).not.toBeNull();
  expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(844);

  await page.getByRole("button", { name: "대화 목록 열기" }).click();
  const drawer = page.getByRole("complementary", { name: "대화 목록" });
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0);
  await drawer.getByRole("button", { name: /모바일 대화 5/ }).click();
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? 0).toBeLessThan(0);

  await page.getByRole("button", { name: "대화 목록 열기" }).click();
  await page.getByRole("button", { name: "대화 목록 닫기" }).click({ position: { x: 380, y: 400 } });
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? 0).toBeLessThan(0);
});

test("desktop에서 대화 목록을 고정 칸으로 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop");
  await page.goto("/");

  const drawer = page.getByRole("complementary", { name: "대화 목록" });
  await expect(drawer).toBeVisible();
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0);
  await expect(page.getByRole("button", { name: "대화 목록 열기" })).toBeHidden();
});

test("에이전트 답의 표를 그리고 HTML은 실행하지 않는다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByPlaceholder("무엇을 도와줄까요");
  await composer.fill("마크다운 보안 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  await expect(page.getByRole("table").last()).toBeVisible();
  await expect(page.getByText("<script>window.__unsafeAgentHtml = true</script>").last()).toBeVisible();
  expect(await page.evaluate(() => (window as typeof window & { __unsafeAgentHtml?: boolean }).__unsafeAgentHtml)).toBeUndefined();
});

test("코드 블록의 역할별 색을 밝음과 어두움에서 구분한다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByPlaceholder("무엇을 도와줄까요");
  await composer.fill("코드 블록 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  const classes = ["keyword", "string", "number", "function", "type", "comment"];
  for (const name of classes) {
    await expect(page.locator(`.text-code-${name}`).first()).toBeVisible();
  }

  async function colors(dark: boolean) {
    await page.locator("html").evaluate((html, enabled) => html.classList.toggle("dark", enabled), dark);
    return page.evaluate((names) => Object.fromEntries(names.map((name) => {
      const element = document.querySelector(`.text-code-${name}`);
      return [name, element ? getComputedStyle(element).color : null];
    })), classes);
  }

  const light = await colors(false);
  const dark = await colors(true);
  expect(new Set(Object.values(light)).size).toBe(classes.length);
  expect(new Set(Object.values(dark)).size).toBe(classes.length);
  for (const name of classes) expect(dark[name]).not.toBe(light[name]);
  await expect(page.locator(".text-code-comment").first()).toHaveCSS("font-style", "italic");
});

test("위로 올려 읽는 동안 새 답이 와도 읽던 자리를 지킨다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByPlaceholder("무엇을 도와줄까요");
  await composer.fill("긴 답 스트림 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  const scroll = page.getByTestId("message-scroll");
  await expect(page.getByText("1번째 긴 답 줄", { exact: true }).last()).toBeVisible();
  await scroll.evaluate((element) => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event("scroll"));
  });
  const position = await scroll.evaluate((element) => element.scrollTop);
  await expect(page.getByRole("button", { name: "새 메시지" })).toBeVisible();
  expect(await scroll.evaluate((element) => element.scrollTop)).toBe(position);
});
