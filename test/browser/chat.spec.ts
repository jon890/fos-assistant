import { CONVERSATION_URL, expect, test, SWITCH_AGENT_CODE } from "./fixtures.ts";

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

  const composer = page.getByRole("textbox", { name: "메시지" });
  const box = await composer.boundingBox();
  expect(box).not.toBeNull();
  expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(844);

  await page.getByRole("button", { name: "사이드바 열기" }).click();
  const drawer = page.getByRole("complementary", { name: "사이드바" });
  await expect(drawer).toBeInViewport();
  await drawer.getByRole("link", { name: /모바일 대화 5/ }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(drawer).toBeHidden();

  await page.getByRole("button", { name: "사이드바 열기" }).click();
  await page.getByRole("button", { name: "사이드바 닫기" }).click();
  await expect(drawer).toBeHidden();

  // 서랍 오른쪽 바깥의 덮개를 눌러도 닫힌다.
  await page.getByRole("button", { name: "사이드바 열기" }).click();
  await expect(drawer).toBeInViewport();
  await page.mouse.click(370, 400);
  await expect(drawer).toBeHidden();
});

test("desktop에서 대화 목록을 고정 칸으로 보인다", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop");
  await page.goto("/");

  const drawer = page.getByRole("complementary", { name: "사이드바" });
  await expect(drawer).toBeVisible();
  await expect.poll(async () => (await drawer.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0);
  await expect(page.getByRole("button", { name: "사이드바 열기" })).toBeHidden();
});

test("내 말과 비서 답을 서로 다른 폭으로 배치하고 입력창을 알약 하나로 보인다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  const send = page.getByRole("button", { name: "보내기" });
  await expect(send).toBeDisabled();

  await composer.fill("말풍선 배치 검사");
  await expect(send).toBeEnabled();
  await page.mouse.move(0, 0);
  const sendColors = await send.evaluate((button) => ({
    background: getComputedStyle(button).backgroundColor,
    primary: getComputedStyle(document.documentElement).getPropertyValue("--primary").trim(),
  }));
  expect(sendColors.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(sendColors.background).toBe("rgb(176, 90, 60)");
  expect(sendColors.primary).toBe("#b05a3c");
  await send.click();
  await expect(composer).toBeEnabled();

  const userMessage = page.getByTestId("user-message").last();
  const assistantMessage = page.getByTestId("assistant-message").last();
  await expect(userMessage).toBeVisible();
  await expect(assistantMessage).toBeVisible();
  const userTime = userMessage.locator("time");
  const assistantTime = assistantMessage.locator("time");
  // 저장된 이력을 다시 읽어 시각과 답의 동작 줄이 생긴 뒤에 잰다. 그 전에 재면 그 줄이 늘어난 것까지 섞인다.
  await expect(userTime).toHaveCount(1);
  await expect(assistantTime).toHaveCount(1);
  await expect(assistantMessage.getByRole("button", { name: "답 다시 만들기" })).toBeVisible();
  await expect(userTime).toBeHidden();
  await expect(assistantTime).toBeHidden();
  // 시각이 보였다 숨었다 해도 말풍선과 답의 크기가 그대로여야 한다. 아래 메시지가 밀리면 안 된다.
  const userBefore = await userMessage.boundingBox();
  const assistantBefore = await assistantMessage.boundingBox();
  await userMessage.hover();
  await expect(userTime).toBeVisible();
  expect(await userMessage.boundingBox()).toEqual(userBefore);
  await assistantMessage.focus();
  await expect(assistantTime).toBeVisible();
  expect(await assistantMessage.boundingBox()).toEqual(assistantBefore);

  const userBox = await userMessage.boundingBox();
  const assistantBox = await assistantMessage.boundingBox();
  expect(userBox).not.toBeNull();
  expect(assistantBox).not.toBeNull();
  expect(
    Math.abs(
      (userBox?.x ?? 0)
      + (userBox?.width ?? 0)
      - ((assistantBox?.x ?? 0) + (assistantBox?.width ?? 0)),
    ),
  ).toBeLessThanOrEqual(1);
  expect(userBox?.x ?? 0).toBeGreaterThan(assistantBox?.x ?? 0);
  expect(userBox?.width ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(
    (assistantBox?.width ?? 0) * 0.7 + 1,
  );

  const composerShell = page.getByTestId("composer-shell");
  const shellBox = await composerShell.boundingBox();
  const sendBox = await send.boundingBox();
  expect(shellBox).not.toBeNull();
  expect(sendBox).not.toBeNull();
  expect(sendBox?.x ?? 0).toBeGreaterThanOrEqual(
    shellBox?.x ?? Number.POSITIVE_INFINITY,
  );
  expect((sendBox?.x ?? 0) + (sendBox?.width ?? 0)).toBeLessThanOrEqual(
    (shellBox?.x ?? 0) + (shellBox?.width ?? 0),
  );
  expect(sendBox?.y ?? 0).toBeGreaterThanOrEqual(
    shellBox?.y ?? Number.POSITIVE_INFINITY,
  );
  expect((sendBox?.y ?? 0) + (sendBox?.height ?? 0)).toBeLessThanOrEqual(
    (shellBox?.y ?? 0) + (shellBox?.height ?? 0),
  );
});

test("에이전트 답의 표를 그리고 HTML은 실행하지 않는다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("마크다운 보안 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  await expect(page.getByRole("table").last()).toBeVisible();
  await expect(page.getByText("<script>window.__unsafeAgentHtml = true</script>").last()).toBeVisible();
  expect(await page.evaluate(() => (window as typeof window & { __unsafeAgentHtml?: boolean }).__unsafeAgentHtml)).toBeUndefined();
});

test("코드 블록의 역할별 색을 밝음과 어두움에서 구분한다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
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

test("위로 올려 읽는 동안 다음 답이 와도 읽던 자리를 지킨다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill("긴 답 스트림 검사");
  await page.getByRole("button", { name: "보내기" }).click();

  const scroll = page.getByTestId("message-scroll");
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await composer.fill("읽는 중 다음 답 검사");
  await expect(page.getByRole("button", { name: "보내기" })).toBeEnabled();
  await expect.poll(async () => scroll.evaluate((element) => element.scrollHeight - element.clientHeight))
    .toBeGreaterThan(100);
  await scroll.evaluate((element) => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event("scroll"));
  });
  const position = await scroll.evaluate((element) => element.scrollTop);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  await expect(page.getByRole("button", { name: "새 메시지" })).toBeVisible();
  expect(await scroll.evaluate((element) => element.scrollTop)).toBe(position);
});

test("막힌 모델을 고른 대화는 넘기지 않고 실패한다", async ({ page, hermes }, testInfo) => {
  const blockedProvider = `blocked-${testInfo.project.name}`;
  const created = await page.request.post("/api/chat/conversations", {
    data: { agentCode: SWITCH_AGENT_CODE },
  });
  expect(created.ok(), `빈 대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  const chosen = await page.request.put(`/api/chat/conversations/${conversationId}/model`, {
    data: { provider: blockedProvider, model: "blocked-model", reasoningEffort: null },
  });
  expect(chosen.ok(), `모델을 고르지 못했다: ${chosen.status()}`).toBeTruthy();

  await hermes.blockProvider(blockedProvider);
  try {
    const response = await page.request.post("/api/chat", {
      data: { conversationId, text: "막힘 화면 검사" },
    });
    expect(response.ok()).toBeFalsy();
    expect(((await response.json()) as { code: string }).code).toBe("PROVIDER_BLOCKED");
  } finally {
    await hermes.clearBlockedProviders();
  }

  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("user-message").last()).toContainText("막힘 화면 검사");
  await expect(page.getByTestId("provider-switched")).toHaveCount(0);
});
