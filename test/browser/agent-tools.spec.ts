import { expect, setAgentVisibility, setSession, test } from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

const AGENT_CODE = "browser";

function toolsSection(page: Page) {
  return page.getByRole("region", { name: "도구" });
}

function toolRow(page: Page, label: string) {
  return toolsSection(page).locator("li").filter({ hasText: label });
}

async function makePrivate(page: Page, ownerEmail = TEST_EMAIL) {
  const response = await page.request.patch(`/api/admin/agents/${AGENT_CODE}`, {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail },
  });
  if (!response.ok()) throw new Error(`에이전트를 비공개로 바꾸지 못했다: ${response.status()} ${await response.text()}`);
}

async function disableConfigurableTools(page: Page) {
  const response = await page.request.put(`/api/admin/agents/${AGENT_CODE}/tools`, {
    data: { enabled: [] },
  });
  expect(response.ok()).toBeTruthy();
}

test("주인이 web 도구를 켜면 다시 열어도 켜져 있고 관리자 도구는 누를 수 없다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await makePrivate(page, "member@example.com");
  await disableConfigurableTools(page);
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    const web = toolRow(page, "웹 검색");
    await expect(web.getByRole("switch")).toHaveAttribute("aria-checked", "false");
    await web.getByRole("switch").click();
    await expect(web.getByRole("switch")).toHaveAttribute("aria-checked", "true");

    await page.reload();
    await expect(toolRow(page, "웹 검색").getByRole("switch")).toHaveAttribute("aria-checked", "true");
    await expect(toolRow(page, "명령 실행").getByRole("switch")).toHaveAttribute("aria-checked", "false");
    await expect(toolRow(page, "명령 실행").getByRole("switch")).toBeDisabled();
    await expect(toolRow(page, "명령 실행").getByText("관리자만 켤 수 있어요")).toBeVisible();
  } finally {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await makePrivate(page);
    await disableConfigurableTools(page);
  }
});

test("관리자가 terminal 도구를 켤 때 확인 창을 거친다", async ({ page }) => {
  await makePrivate(page);
  await disableConfigurableTools(page);
  await page.goto(`/agents/${AGENT_CODE}`);
  const terminal = toolRow(page, "명령 실행");
  await expect(terminal.getByRole("switch")).toHaveAttribute("aria-checked", "false");
  await terminal.getByRole("switch").click();

  const dialog = page.getByRole("alertdialog", { name: "명령 실행 도구 켜기" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("이 도구는 홈서버 파일과 셸에 닿을 수 있어요.")).toBeVisible();
  await dialog.getByRole("button", { name: "켜기" }).click();
  await expect(terminal.getByRole("switch")).toHaveAttribute("aria-checked", "true");

  await terminal.getByRole("switch").click();
  await expect(terminal.getByRole("switch")).toHaveAttribute("aria-checked", "false");
});

test("관리자는 다른 주인의 비공개 에이전트 도구를 관리자 경로로 고친다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await makePrivate(page, "member@example.com");
  await disableConfigurableTools(page);
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    const terminal = toolRow(page, "명령 실행");
    await terminal.getByRole("switch").click();
    const response = page.waitForResponse((candidate) =>
      candidate.url().endsWith(`/api/admin/agents/${AGENT_CODE}/tools`)
        && candidate.request().method() === "PUT",
    );
    await page.getByRole("alertdialog", { name: "명령 실행 도구 켜기" })
      .getByRole("button", { name: "켜기" })
      .click();
    expect((await response).ok()).toBeTruthy();
    await expect(terminal.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  } finally {
    await makePrivate(page);
    await disableConfigurableTools(page);
  }
});

test("그룹 공개 에이전트를 읽는 사용자는 성격만 보고 도구 절은 보지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await makePrivate(page);
  await disableConfigurableTools(page);
  await setAgentVisibility(AGENT_CODE, "GROUP", null);
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(page.getByRole("textbox", { name: "브라우저 비서 성격" })).toBeVisible();
    await expect(toolsSection(page)).toHaveCount(0);
  } finally {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await makePrivate(page);
    await disableConfigurableTools(page);
  }
});

test("그룹 공개 에이전트에서는 셸과 파일 도구를 누를 수 없다", async ({ page }) => {
  await makePrivate(page);
  await disableConfigurableTools(page);
  await setAgentVisibility(AGENT_CODE, "GROUP", null);
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    for (const label of ["명령 실행", "파일", "코드 실행", "브라우저", "컴퓨터 조작"]) {
      const row = toolRow(page, label);
      await expect(row.getByRole("switch")).toHaveAttribute("aria-checked", "false");
      await expect(row.getByRole("switch")).toBeDisabled();
      await expect(row.getByText("그룹 공개 에이전트에는 켤 수 없어요")).toBeVisible();
    }
  } finally {
    await makePrivate(page);
  }
});

test("저장 뒤 도구가 빠지면 다시 읽은 상태와 안내를 보인다", async ({ page }) => {
  await makePrivate(page);
  await disableConfigurableTools(page);
  const refreshed = {
    toolsets: [
      { name: "web", label: "Web", description: "웹을 검색한다", tier: "OWNER", enabled: false, editable: true, requiresPrivate: false },
      { name: "terminal", label: "Terminal", description: "명령을 실행한다", tier: "ADMIN", enabled: false, editable: true, requiresPrivate: true },
    ],
    unclassifiedEnabled: ["profile-only-tool"],
  };
  let failed = false;
  await page.route(`**/api/agents/${AGENT_CODE}/tools`, async (route) => {
    if (route.request().method() === "PUT") {
      failed = true;
      return route.fulfill({
        status: 502,
        contentType: "application/json",
        body: JSON.stringify({
          code: "AGENT_TOOLS_NOT_APPLIED",
          message: "Hermes did not apply the requested toolsets",
          missingToolsets: ["web"],
        }),
      });
    }
    if (route.request().method() === "GET" && failed) {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(refreshed) });
    }
    return route.continue();
  });

  await page.goto(`/agents/${AGENT_CODE}`);
  const web = toolRow(page, "웹 검색");
  await web.getByRole("switch").click();
  await expect(web.getByRole("switch")).toHaveAttribute("aria-checked", "false");
  await expect(web.getByText("이 도구를 켜지 못했어요. 관리자에게 알려 주세요.")).toBeVisible();
  await expect(toolsSection(page).getByText("표에 없는 도구가 켜져 있어요. 관리자에게 알려 주세요.")).toBeVisible();
});

test("저장하는 동안 누른 도구에만 저장 중 표시를 한다", async ({ page }) => {
  await makePrivate(page);
  await disableConfigurableTools(page);
  let release = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route(`**/api/agents/${AGENT_CODE}/tools`, async (route) => {
    if (route.request().method() === "PUT") await held;
    await route.continue();
  });

  await page.goto(`/agents/${AGENT_CODE}`);
  const web = toolRow(page, "웹 검색");
  await web.getByRole("switch").click();
  try {
    await expect(web.getByRole("switch")).toHaveAttribute("aria-busy", "true");
    const vision = toolRow(page, "사진 보기").getByRole("switch");
    await expect(vision).toBeVisible();
    await expect(vision).toHaveAttribute("aria-checked", "false");
    await expect(vision).toBeDisabled();
  } finally {
    release();
  }
  await expect(web.getByRole("switch")).toHaveAttribute("aria-checked", "true");
});

test("스위치를 Space 로 켜고 끈다", async ({ page }) => {
  await makePrivate(page);
  await disableConfigurableTools(page);
  try {
    await page.goto(`/agents/${AGENT_CODE}`);
    const web = toolRow(page, "웹 검색").getByRole("switch", { name: "웹 검색 도구" });
    await expect(web).toHaveAttribute("aria-checked", "false");
    await web.focus();
    await expect(web).toBeFocused();
    await page.keyboard.press("Space");
    await expect(web).toHaveAttribute("aria-checked", "true");
    // 저장이 끝나 잠금이 풀린 뒤에 다시 누른다.
    await expect(web).toBeEnabled();
    await web.focus();
    await page.keyboard.press("Space");
    await expect(web).toHaveAttribute("aria-checked", "false");
    await expect(web).toBeEnabled();

    // 그룹 공개 에이전트에서는 Terminal 스위치가 잠긴다. 잠긴 스위치는 눌러도 바뀌지 않고 확인 창도 뜨지 않는다.
    await setAgentVisibility(AGENT_CODE, "GROUP", null);
    await page.reload();
    const terminal = toolRow(page, "명령 실행").getByRole("switch", { name: "명령 실행 도구" });
    await expect(terminal).toBeDisabled();
    await expect(terminal).toHaveAttribute("aria-checked", "false");
    await terminal.click({ force: true });
    await terminal.dispatchEvent("click");
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect(terminal).toHaveAttribute("aria-checked", "false");
    await page.reload();
    await expect(toolRow(page, "명령 실행").getByRole("switch")).toHaveAttribute("aria-checked", "false");
  } finally {
    await makePrivate(page);
    await disableConfigurableTools(page);
  }
});
