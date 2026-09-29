import { CONVERSATION_URL, expect, setSession, test } from "./fixtures.ts";
import type { BrowserContext, Page } from "../../web/node_modules/@playwright/test/index.js";

/**
 * 사용자가 에이전트를 만들고 공개 범위를 바꾸고 지우는 흐름을 본다.
 *
 * <p>mobile 과 desktop 이 같은 Control Plane 을 함께 쓰고 사용자마다 만들 수 있는 수가 정해져 있어서,
 * project 마다 다른 사용자로 로그인한다. 이 검사가 만든 에이전트는 검사가 끝날 때마다 지운다. 그룹에 공개한
 * 에이전트가 남으면 관리 화면의 그룹 공개 에이전트가 하나라고 가정하는 다른 검사가 어긋난다.
 */

const NAME = "숙제 도우미";
const AGENT_LIMIT = 5;

function ownerOf(projectName: string) {
  return { email: `lifecycle-${projectName}@example.com`, name: "에이전트 만드는 사용자" };
}

function otherOf(projectName: string) {
  return { email: `lifecycle-other-${projectName}@example.com`, name: "다른 사용자" };
}

/** 이 사용자로 로그인하고 계정이 만들어지게 한다. */
async function loginAs(context: BrowserContext, page: Page, user: { email: string; name: string }) {
  await setSession(context, user);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
}

type Listed = { code: string; ownedByMe: boolean };

/** 로그인한 사용자가 만든 에이전트를 API 로 만들고 코드를 돌려준다. */
async function createAgent(page: Page, visibility?: "GROUP"): Promise<string> {
  const response = await page.request.post("/api/agents", { data: { name: NAME, visibility } });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { code: string }).code;
}

async function listedCodes(page: Page): Promise<string[]> {
  const response = await page.request.get("/api/agents");
  expect(response.ok()).toBeTruthy();
  return ((await response.json()) as Listed[]).map((agent) => agent.code);
}

function accessSection(page: Page) {
  return page.getByRole("region", { name: "공개와 삭제" });
}

test.beforeEach(async ({ context, page }, testInfo) => {
  await loginAs(context, page, ownerOf(testInfo.project.name));
});

test.afterEach(async ({ context, page }, testInfo) => {
  await setSession(context, ownerOf(testInfo.project.name));
  const response = await page.request.get("/api/agents");
  const mine = ((await response.json()) as Listed[]).filter((agent) => agent.ownedByMe);
  for (const agent of mine) {
    const deleted = await page.request.delete(`/api/agents/${agent.code}`);
    expect(deleted.status(), `검사가 만든 에이전트 ${agent.code} 를 지우지 못했다`).toBe(204);
  }
});

test("이름만 넣어 에이전트를 만들면 상세로 가고 그 에이전트와 대화가 끝난다", async ({ page }) => {
  // 다른 검사가 남긴 그룹 공개 에이전트가 목록에 있을 수 있어 빈 상태 문구는 보지 않는다.
  await page.goto("/agents");
  await expect(page.getByRole("button", { name: "새 에이전트", exact: true })).toBeVisible();

  // 만드는 동안 단추가 막히고 진행 중 문구가 보인다.
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let postSeen = false;
  await page.route("**/api/agents", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    postSeen = true;
    await held;
    await route.continue();
  });

  await page.getByRole("button", { name: "새 에이전트", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "새 에이전트" });
  await expect(dialog.getByLabel("공개 범위")).toHaveValue("PRIVATE");
  await dialog.getByLabel("이름").fill(NAME);
  await dialog.getByRole("button", { name: "만들기" }).click();

  await expect.poll(() => postSeen).toBe(true);
  const busy = dialog.getByRole("button", { name: "만드는 중…" });
  await expect(busy).toHaveAttribute("aria-busy", "true");
  await expect(busy).toBeDisabled();
  release();

  await expect(page).toHaveURL(/\/agents\/[a-z0-9][a-z0-9-]*$/);
  await expect(page.getByRole("textbox", { name: `${NAME} 성격` })).toBeVisible();
  await expect(accessSection(page).getByText("나만", { exact: true })).toBeVisible();

  // 새 대화 화면에서 그 에이전트로 보낸 질문이 답으로 끝난다.
  const question = "받아쓰기 연습을 도와줘";
  await page.goto("/");
  await expect(page.getByRole("main").getByText(NAME, { exact: true })).toBeVisible();
  await page.getByRole("textbox", { name: "메시지" }).fill(question);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toContainText(question, { timeout: 30_000 });
});

test("상한만큼 만든 뒤 다시 만들면 대화상자 안에 상한 문구가 보인다", async ({ page }) => {
  for (let count = 0; count < AGENT_LIMIT; count += 1) await createAgent(page);

  await page.goto("/agents");
  await page.getByRole("button", { name: "새 에이전트", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "새 에이전트" });
  await dialog.getByLabel("이름").fill(NAME);
  await dialog.getByRole("button", { name: "만들기" }).click();

  await expect(dialog.getByRole("alert")).toHaveText("에이전트는 5개까지 만들 수 있어요");
  await expect(dialog).toBeVisible();
  await expect(page).toHaveURL(/\/agents$/);
  await expect(dialog.getByRole("button", { name: "만들기" })).toBeEnabled();
  expect(await listedCodes(page)).toHaveLength(AGENT_LIMIT);
});

/** 에이전트를 만드는 요청을 이 응답으로 바꾼다. */
async function failCreate(page: Page, status: number, contentType: string, body: string) {
  await page.route("**/api/agents", (route) => route.request().method() === "POST"
    ? route.fulfill({ status, contentType, body })
    : route.continue());
}

async function submitCreate(page: Page) {
  await page.goto("/agents");
  await page.getByRole("button", { name: "새 에이전트", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "새 에이전트" });
  await dialog.getByLabel("이름").fill(NAME);
  await dialog.getByRole("button", { name: "만들기" }).click();
  return dialog;
}

test("만들기가 profile 생성에 실패하면 사용자 추가가 아니라 에이전트 문구가 보인다", async ({ page }) => {
  await failCreate(page, 502, "application/json", JSON.stringify({ code: "HERMES_PROVISION_FAILED", message: "provision failed" }));

  const dialog = await submitCreate(page);

  await expect(dialog.getByRole("alert")).toHaveText("에이전트를 만들지 못했어요. 다시 시도해 주세요.");
  await expect(dialog.getByRole("button", { name: "만들기" })).toBeEnabled();
});

test("만들기가 입력 검증에 실패하면 backend 원문 대신 이름 길이 안내가 보인다", async ({ page }) => {
  await failCreate(page, 400, "application/json", JSON.stringify({ code: "VALIDATION_FAILED", message: "name must be between 1 and 100" }));

  const dialog = await submitCreate(page);

  await expect(dialog.getByRole("alert")).toHaveText("이름은 1자 이상 100자 이하로 적어 주세요.");
});

test("만들기의 오류 응답이 JSON 이 아니면 연결 문구가 아니라 처리 실패 문구가 보인다", async ({ page }) => {
  await failCreate(page, 502, "text/html", "<html>Bad Gateway</html>");

  const dialog = await submitCreate(page);

  await expect(dialog.getByRole("alert")).toHaveText("요청을 처리하지 못했어요.");
});

test("지우기에 권한이 없으면 backend 원문 대신 관리 권한 안내가 보인다", async ({ page }) => {
  const code = await createAgent(page);
  await page.route(`**/api/agents/${code}`, (route) => route.request().method() === "DELETE"
    ? route.fulfill({
      status: 403,
      contentType: "application/json",
      body: JSON.stringify({ code: "FORBIDDEN", message: "forbidden" }),
    })
    : route.continue());

  await page.goto(`/agents/${code}`);
  await accessSection(page).getByRole("button", { name: "에이전트 지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: `${NAME} 에이전트를 지울까요?` });
  await dialog.getByRole("button", { name: "지우기" }).click();

  await expect(dialog.getByRole("alert")).toHaveText("이 에이전트를 관리할 수 없어요.");
});

test("공개 범위 변경이 입력 검증에 실패하면 backend 원문 대신 다시 시도 안내가 보인다", async ({ page }) => {
  const code = await createAgent(page);
  await page.route(`**/api/agents/${code}/visibility`, (route) => route.request().method() === "PATCH"
    ? route.fulfill({
      status: 400,
      contentType: "application/json",
      body: JSON.stringify({ code: "VALIDATION_FAILED", message: "visibility must be PRIVATE or GROUP" }),
    })
    : route.continue());

  await page.goto(`/agents/${code}`);
  await accessSection(page).getByRole("button", { name: "그룹 공개로 변경" }).click();
  const dialog = page.getByRole("alertdialog", { name: `${NAME} 에이전트를 그룹에 공개할까요?` });
  await dialog.getByRole("button", { name: "그룹 공개" }).click();

  await expect(dialog.getByRole("alert")).toHaveText("공개 범위를 바꾸지 못했어요. 다시 시도해 주세요.");
});

test("그룹 공개로 바꾸면 확인 창을 거쳐 다른 사용자의 목록에 보이고 만든 사람은 성격을 고칠 수 있다", async ({ context, page }, testInfo) => {
  const code = await createAgent(page);
  await loginAs(context, page, otherOf(testInfo.project.name));
  expect(await listedCodes(page)).not.toContain(code);
  await setSession(context, ownerOf(testInfo.project.name));

  await page.goto(`/agents/${code}`);
  const section = accessSection(page);
  await expect(section.getByText("나만", { exact: true })).toBeVisible();
  await section.getByRole("button", { name: "그룹 공개로 변경" }).click();
  const dialog = page.getByRole("alertdialog", { name: `${NAME} 에이전트를 그룹에 공개할까요?` });
  await expect(dialog.getByText("그룹의 모든 사용자가 이 에이전트와 대화할 수 있어요. 각자의 대화와 기억은 서로 보이지 않아요.")).toBeVisible();
  await dialog.getByRole("button", { name: "그룹 공개" }).click();
  await expect(dialog).toBeHidden();
  await expect(section.getByText("그룹 공개", { exact: true })).toBeVisible();

  // 새로 읽어도 만든 사람은 성격을 고칠 수 있다.
  await page.reload();
  const persona = page.getByRole("textbox", { name: `${NAME} 성격` });
  await expect(persona).toBeVisible();
  await expect(persona).not.toHaveAttribute("readonly", "");
  await expect(page.getByRole("button", { name: "저장", exact: true })).toBeVisible();

  await loginAs(context, page, otherOf(testInfo.project.name));
  expect(await listedCodes(page)).toContain(code);

  // 다시 나만으로 바꾸는 것은 확인 없이 된다.
  await setSession(context, ownerOf(testInfo.project.name));
  await page.goto(`/agents/${code}`);
  await accessSection(page).getByRole("button", { name: "나만으로 변경" }).click();
  await expect(accessSection(page).getByText("나만", { exact: true })).toBeVisible();
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
});

test("지우면 확인 창을 거쳐 목록에서 빠진다", async ({ page }) => {
  const code = await createAgent(page);

  await page.goto(`/agents/${code}`);
  await accessSection(page).getByRole("button", { name: "에이전트 지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: `${NAME} 에이전트를 지울까요?` });
  await expect(dialog.getByText("에이전트를 지우면 새 대화를 시작할 수 없어요. 지난 대화는 읽을 수 있어요.")).toBeVisible();

  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();
  expect(await listedCodes(page)).toContain(code);

  await accessSection(page).getByRole("button", { name: "에이전트 지우기" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "지우기" }).click();

  await expect(page).toHaveURL(/\/agents$/);
  await expect(page.locator(`a[href="/agents/${code}"]`)).toHaveCount(0);
  expect(await listedCodes(page)).not.toContain(code);
});

test("지우기가 실패하면 창이 남고 실패 까닭이 보인다", async ({ page }) => {
  const code = await createAgent(page);
  await page.route(`**/api/agents/${code}`, (route) => route.request().method() === "DELETE"
    ? route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({ code: "INTERNAL_ERROR", message: "에이전트를 지우지 못했다" }),
    })
    : route.continue());

  await page.goto(`/agents/${code}`);
  await accessSection(page).getByRole("button", { name: "에이전트 지우기" }).click();
  const dialog = page.getByRole("alertdialog", { name: `${NAME} 에이전트를 지울까요?` });
  await dialog.getByRole("button", { name: "지우기" }).click();

  await expect(dialog.getByText("에이전트를 지우지 못했다", { exact: true })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "지우기" })).toBeEnabled();
  await expect(page).toHaveURL(new RegExp(`/agents/${code}$`));
});

test("다른 사용자의 상세에는 「공개와 삭제」 절이 없다", async ({ context, page }, testInfo) => {
  const code = await createAgent(page, "GROUP");

  await loginAs(context, page, otherOf(testInfo.project.name));
  await page.goto(`/agents/${code}`);

  await expect(page.getByRole("textbox", { name: `${NAME} 성격` })).toBeVisible();
  await expect(accessSection(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "에이전트 지우기" })).toHaveCount(0);
});
