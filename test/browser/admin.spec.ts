import { expect, hermesBaseUrl, setSession, test, PERSONA_AGENT_CODE } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

/** 에이전트가 여럿이라 검사가 보는 카드 하나로 좁힌다. */
function browserCard(page: Page) {
  return page
    .getByRole("region", { name: "등록된 에이전트" })
    .locator("article")
    .filter({ hasText: "브라우저 비서" });
}

function adminSection(page: Page) {
  return page.getByRole("region", { name: "관리" });
}

function accessSection(page: Page) {
  return page.getByRole("region", { name: "공개와 삭제" });
}

test("관리자 에이전트 목록에서 등록한 에이전트가 보인다", async ({ page }) => {
  const registered = {
    id: 99,
    code: "registered-agent",
    name: "새 에이전트",
    hermesProfile: "registered-profile",
    apiBaseUrl: "http://example.test/p/registered-profile",
    model: "example-model",
    costMode: "SUBSCRIPTION",
    credentialScope: "SHARED_HOUSEHOLD",
    visibility: "PRIVATE",
    ownerUserId: 1,
    enabled: true,
    flow: null,
  };
  await page.route(/\/api\/admin\/agents(?:\?.*)?$/, async (route) => {
    if (route.request().method() === "POST") return route.fulfill({ status: 201, json: registered });
    if (route.request().method() === "GET") return route.fulfill({ json: [registered] });
    await route.continue();
  });

  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "에이전트 등록" })).toBeVisible();
  const form = page.getByRole("heading", { name: "에이전트 등록" }).locator("xpath=ancestor::form");
  await form.getByLabel("코드").fill(registered.code);
  await form.getByLabel("이름").fill(registered.name);
  await form.getByLabel("profile").fill(registered.hermesProfile);
  await form.getByLabel("에이전트 연결 주소").fill(registered.apiBaseUrl);
  // 에이전트는 모델을 갖지 않으므로 등록 양식이 모델 제공사를 묻지 않는다.
  await expect(form.getByLabel("모델 제공사")).toHaveCount(0);
  await form.getByRole("button", { name: "등록" }).click();

  const card = page.getByRole("region", { name: "등록된 에이전트" }).locator("article");
  await expect(card.getByRole("heading", { name: registered.name })).toBeVisible();
  await expect(card.getByText(registered.model, { exact: true })).toHaveCount(0);
  await expect(card.getByText(registered.code, { exact: true })).toHaveCount(0);
  await expect(card.getByText(registered.hermesProfile, { exact: true })).toHaveCount(0);
});

test("관리자 목록은 다른 사람의 비공개 에이전트와 꺼진 에이전트를 표시한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: "browser@example.com", name: "브라우저 테스트" });

  const changed = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: false, visibility: "PRIVATE", ownerEmail: "member@example.com" },
  });
  expect(changed.ok()).toBeTruthy();
  try {
    await page.goto("/agents");
    const card = browserCard(page);
    await expect(card.getByText("다른 사람 것", { exact: true })).toBeVisible();
    await expect(card.getByText("꺼짐", { exact: true })).toBeVisible();
  } finally {
    const reset = await page.request.patch("/api/admin/agents/browser", {
      data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
    });
    expect(reset.ok()).toBeTruthy();
  }
});

test("MEMBER는 관리 목록과 옛 관리 주소를 쓰지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });

  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "에이전트 등록" })).toHaveCount(0);
  await expect(page.getByText("다른 사람 것", { exact: true })).toHaveCount(0);
  await expect(page.locator('a[href="/agents/browser"]')).toHaveCount(0);
  await expect(page.getByRole("link", { name: "에이전트 관리" })).toHaveCount(0);

  await page.goto("/admin/agents");
  await expect(page).toHaveURL(/\/agents$/);
});

test("그룹 공개로 바꾸기 전에 확인하고 취소와 확인을 반영한다", async ({ page }) => {
  const reset = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
  });
  expect(reset.ok()).toBeTruthy();
  try {
    await page.goto("/agents/browser");
    const section = accessSection(page);

    await expect(section.getByText("나만", { exact: true })).toBeVisible();
    await section.getByRole("button", { name: "그룹 공개로 변경" }).click();
    const dialog = page.getByRole("alertdialog", { name: "브라우저 비서 에이전트를 그룹에 공개할까요?" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("그룹의 모든 사용자가 이 에이전트와 대화할 수 있어요. 각자의 대화와 기억은 서로 보이지 않아요.")).toBeVisible();
    await dialog.getByRole("button", { name: "취소" }).click();
    await expect(dialog).toBeHidden();
    await expect(section.getByText("나만", { exact: true })).toBeVisible();

    await section.getByRole("button", { name: "그룹 공개로 변경" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "그룹 공개" }).click();
    await expect(section.getByText("그룹 공개", { exact: true })).toBeVisible();
  } finally {
    const restored = await page.request.patch("/api/admin/agents/browser", {
      data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
    });
    expect(restored.ok()).toBeTruthy();
  }
});

/** 브라우저 비서를 「나만」 으로 되돌리고 「공개와 삭제」 절에서 공개 확인 창을 연다. */
async function openVisibilityConfirm(page: Page) {
  const reset = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
  });
  expect(reset.ok()).toBeTruthy();
  await page.goto("/agents/browser");
  await accessSection(page).getByRole("button", { name: "그룹 공개로 변경" }).click();
  const dialog = page.getByRole("alertdialog", { name: "브라우저 비서 에이전트를 그룹에 공개할까요?" });
  await expect(dialog).toBeVisible();
  return dialog;
}

test("공개 확인 창에서 Esc 를 누르면 창이 닫히고 공개 범위가 그대로다", async ({ page }) => {
  const dialog = await openVisibilityConfirm(page);

  await page.keyboard.press("Escape");

  await expect(dialog).toBeHidden();
  await expect(accessSection(page).getByText("나만", { exact: true })).toBeVisible();
});

test("공개 요청이 실패하면 창이 남고 실패 까닭이 보인다", async ({ page }) => {
  // 실패 응답에도 본문을 준다. 화면은 실패 응답의 본문에서 오류 코드와 문구를 읽는다.
  await page.route("**/api/agents/browser/visibility", (route) => route.request().method() === "PATCH"
    ? route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ code: "INTERNAL_ERROR", message: "공개 범위를 바꾸지 못했다" }),
    })
    : route.continue());
  const dialog = await openVisibilityConfirm(page);

  await dialog.getByRole("button", { name: "그룹 공개" }).click();

  await expect(dialog.getByText("공개 범위를 바꾸지 못했다", { exact: true })).toBeVisible();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "그룹 공개" })).toBeEnabled();
  // 창이 열린 동안에는 Radix 가 바깥을 접근성 나무에서 가리므로, 닫은 뒤에 카드를 본다.
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();
  await expect(accessSection(page).getByText("나만", { exact: true })).toBeVisible();
});

test("공개 요청이 도는 동안 Esc 로 닫히지 않고 끝나면 창이 닫힌다", async ({ page }) => {
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let patchSeen = false;
  await page.route("**/api/agents/browser/visibility", async (route) => {
    if (route.request().method() !== "PATCH") return route.continue();
    patchSeen = true;
    await held;
    await route.continue();
  });
  const dialog = await openVisibilityConfirm(page);

  await dialog.getByRole("button", { name: "그룹 공개" }).click();
  await expect.poll(() => patchSeen).toBe(true);
  // 누른 뒤에는 단추 이름이 「공개하는 중」 으로 바뀐다.
  const busy = dialog.getByRole("button", { name: "공개하는 중" });
  await expect(busy).toHaveAttribute("aria-busy", "true");
  await expect(busy).toBeDisabled();
  await expect(dialog.getByRole("button", { name: "취소" })).toBeDisabled();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();

  release();
  await expect(dialog).toBeHidden();
  await expect(accessSection(page).getByText("그룹 공개", { exact: true })).toBeVisible();
});

test("에이전트 목록이 가로로 넘치지 않는다", async ({ page }, testInfo) => {
  await page.goto("/agents");

  const width = testInfo.project.name === "mobile" ? 390 : 1280;
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});

test("Hermes 주소를 고쳐 저장하면 화면에 새 값이 보인다", async ({ page }) => {
  const baseUrl = await hermesBaseUrl();
  const original = `${baseUrl}/p/browser`;
  // 같은 가짜 Hermes 를 가리키면서 글자는 다른 주소다. 확인이 지나가면서 값이 바뀌는 것을 본다.
  const moved = `${original.replace("127.0.0.1", "localhost")}/`;

  await page.goto("/agents/browser");
  const address = adminSection(page).getByLabel("브라우저 비서 에이전트 연결 주소");
  await expect(address).toHaveValue(original);

  await address.fill(moved);
  await adminSection(page).getByRole("button", { name: "주소 저장" }).click();
  // 끝의 슬래시는 떼고 저장한다.
  await expect(address).toHaveValue(moved.slice(0, -1));

  await address.fill(original);
  await adminSection(page).getByRole("button", { name: "주소 저장" }).click();
  await expect(address).toHaveValue(original);
});

test("닿지 않는 주소를 저장하려 하면 실패 이유가 그 자리에 보인다", async ({ page }) => {
  const original = `${await hermesBaseUrl()}/p/browser`;

  await page.goto("/agents/browser");
  const section = adminSection(page);
  await section.getByLabel("브라우저 비서 에이전트 연결 주소").fill("http://127.0.0.1:1/p/browser");
  await section.getByRole("button", { name: "주소 저장" }).click();

  await expect(section.getByRole("alert")).toContainText("could not reach");

  // 저장되지 않았으므로 다시 열면 지금 값이 그대로다.
  await page.goto("/agents/browser");
  await expect(adminSection(page).getByLabel("브라우저 비서 에이전트 연결 주소")).toHaveValue(original);
});

test("상세 관리 절에서 사용 여부를 바꾼다", async ({ page }) => {
  try {
    await page.goto("/agents/browser");
    const section = adminSection(page);

    await section.getByRole("button", { name: "사용 중지" }).click();
    await expect(section.getByText("꺼짐", { exact: true })).toBeVisible();
    await section.getByRole("button", { name: "다시 사용" }).click();
    await expect(section.getByText("사용 중", { exact: true })).toBeVisible();

    await expect(section.getByRole("button", { name: "모델 목록 다시 읽기" })).toHaveCount(0);
    await expect(section.getByTestId("agent-model-list")).toHaveCount(0);
  } finally {
    const restored = await page.request.patch("/api/admin/agents/browser", {
      data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com" },
    });
    expect(restored.ok()).toBeTruthy();
  }
});

test("다른 사람의 비공개 에이전트도 관리하고 수정 뒤 주인을 보존한다", async ({ context, page }) => {
  const original = `${await hermesBaseUrl()}/p/browser`;
  const moved = `${original.replace("127.0.0.1", "localhost")}/`;
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: "browser@example.com", name: "브라우저 테스트" });
  const assigned = await page.request.patch("/api/admin/agents/browser", {
    data: { enabled: true, visibility: "PRIVATE", ownerEmail: "member@example.com" },
  });
  expect(assigned.ok()).toBeTruthy();
  const before = await page.request.get("/api/admin/agents");
  const ownerUserId = ((await before.json()) as { code: string; ownerUserId: number }[]).find((agent) => agent.code === "browser")?.ownerUserId;
  expect(ownerUserId).toBeTruthy();
  const ownerEmails: unknown[] = [];
  page.on("request", (request) => {
    if (request.method() === "PATCH" && request.url().endsWith("/api/admin/agents/browser")) {
      ownerEmails.push(request.postDataJSON().ownerEmail);
    }
  });
  try {
    await page.goto("/agents/browser");
    const section = adminSection(page);
    await expect(page.getByText("이 에이전트의 성격은 주인만 볼 수 있어요.")).toBeVisible();
    await expect(page.getByRole("textbox", { name: "브라우저 비서 성격" })).toHaveCount(0);
    await expect(section).toBeVisible();

    await section.getByLabel("브라우저 비서 에이전트 연결 주소").fill(moved);
    await section.getByRole("button", { name: "주소 저장" }).click();
    await section.getByRole("button", { name: "사용 중지" }).click();
    await section.getByRole("button", { name: "다시 사용" }).click();
    const afterPrivateChanges = await page.request.get("/api/admin/agents");
    expect(((await afterPrivateChanges.json()) as { code: string; ownerUserId: number }[]).find((agent) => agent.code === "browser")?.ownerUserId).toBe(ownerUserId);
    await accessSection(page).getByRole("button", { name: "그룹 공개로 변경" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "그룹 공개" }).click();
    // 공개 범위에 따라 달라지는 절이 새로 고치지 않아도 바뀐다. 성격을 읽을 수 있게 되고 비공개 전용 도구는 막힌다.
    await expect(page.getByRole("textbox", { name: "브라우저 비서 성격" })).toBeVisible();
    await expect(page.getByRole("region", { name: "도구" }).locator("li").filter({ hasText: "명령 실행" })
      .getByText("그룹 공개 에이전트에는 켤 수 없어요")).toBeVisible();
    // 공개 범위를 바꿔도 주인은 그대로다.
    const afterGroup = await page.request.get("/api/admin/agents");
    const grouped = ((await afterGroup.json()) as { code: string; ownerUserId: number; visibility: string }[]).find((agent) => agent.code === "browser");
    expect(grouped?.visibility).toBe("GROUP");
    expect(grouped?.ownerUserId).toBe(ownerUserId);
    // 관리 절의 저장은 공개 범위와 주인을 바꾸지 않는다.
    expect(ownerEmails).toEqual([null, null, null]);
  } finally {
    await setSession(context, { email: "browser@example.com", name: "브라우저 테스트" });
    const reset = await page.request.patch("/api/admin/agents/browser", {
      data: { enabled: true, visibility: "PRIVATE", ownerEmail: "browser@example.com", apiBaseUrl: original },
    });
    expect(reset.ok()).toBeTruthy();
  }
});

test("MEMBER는 상세 화면의 관리 절을 보지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  await expect(adminSection(page)).toHaveCount(0);
  await expect(accessSection(page)).toHaveCount(0);
});
