import {
  expect,
  setSession,
  test as baseTest,
} from "./fixtures.ts";
import { isolatedUser } from "./helpers.ts";
import type { Page } from "@playwright/test";

const test = baseTest.extend<{ personaAgent: { code: string }; initialPersona: string | null }>({
  initialPersona: ["차분하게 답하는 성격이다", { option: true }],
  personaAgent: [async ({ context, page, isolatedMember, initialPersona }, use) => {
    await setSession(context, isolatedMember);
    const created = await page.request.post("/api/agents", {
      data: { name: "성격 비서" },
    });
    expect(created.status()).toBe(201);
    const agent = await created.json() as { code: string };
    try {
      if (initialPersona !== null) {
        const current = await page.request.get(`/api/agents/${agent.code}/persona`);
        expect(current.ok()).toBeTruthy();
        const { bodyHash } = await current.json();
        const saved = await page.request.put(`/api/agents/${agent.code}/persona`, {
          data: { body: initialPersona, baseHash: bodyHash },
        });
        expect(saved.ok(), `성격 준비 요청: ${saved.status()} ${await saved.text()}`).toBeTruthy();
      }
      await use(agent);
    } finally {
      await setSession(context, isolatedMember);
      expect((await page.request.delete(`/api/agents/${agent.code}`)).status()).toBe(204);
    }
  }, { auto: true }],
});

function savePersonaButton(page: Page) {
  const editor = page.getByRole("textbox", { name: /성격$/ }).locator("..");
  return editor.getByRole("button", { name: "저장", exact: true });
}

test("자기 에이전트의 성격 화면을 연다", async ({ page, personaAgent }) => {
  await page.goto(`/agents/${personaAgent.code}`);
  await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  await expect(savePersonaButton(page)).toBeVisible();
});

test("성격을 저장한다", async ({ page, personaAgent }) => {
  await page.goto(`/agents/${personaAgent.code}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("차분하고 다정하게 설명하는 성격이다");
  await savePersonaButton(page).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "저장" }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText("저장했어요.", { exact: true })).toBeVisible();
});

test("확인에서 취소한다", async ({ page, personaAgent }) => {
  const saveRequests: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && request.url().includes(`/api/agents/${personaAgent.code}/persona`)) {
      saveRequests.push(request.url());
    }
  });

  await page.goto(`/agents/${personaAgent.code}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("저장하면 안 되는 성격이다");
  await savePersonaButton(page).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  expect(saveRequests).toHaveLength(0);
});

test("확인 창에서 Esc 를 누르면 창이 닫히고 저장하지 않는다", async ({ page, personaAgent }) => {
  const saveRequests: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && request.url().includes(`/api/agents/${personaAgent.code}/persona`)) {
      saveRequests.push(request.url());
    }
  });

  await page.goto(`/agents/${personaAgent.code}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("Esc 로 물린 성격이다");
  await savePersonaButton(page).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  // 쓰던 글은 편집창에 그대로 남고 저장 요청은 나가지 않는다.
  await expect(textarea).toHaveValue("Esc 로 물린 성격이다");
  expect(saveRequests).toHaveLength(0);
});

test("저장 요청이 도는 동안 저장 단추가 지금 하는 일을 보이고 끝나면 저장되었다고 알린다", async ({ page, personaAgent }) => {
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let saveSeen = false;
  await page.route(`**/api/agents/${personaAgent.code}/persona`, async (route) => {
    if (route.request().method() !== "PUT") return route.continue();
    saveSeen = true;
    await held;
    await route.continue();
  });

  await page.goto(`/agents/${personaAgent.code}`);
  await page.getByRole("textbox", { name: "성격 비서 성격" }).fill("기다리는 동안을 보이는 성격이다");
  await savePersonaButton(page).click();
  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await dialog.getByRole("button", { name: "저장" }).click();
  await expect.poll(() => saveSeen).toBe(true);

  // 누른 뒤에는 확인 창이 닫히고 편집 화면의 단추 이름이 「저장 중」 으로 바뀐다.
  await expect(dialog).toBeHidden();
  const busy = page.getByRole("button", { name: "저장 중" });
  await expect(busy).toHaveAttribute("aria-busy", "true");
  await expect(busy).toBeDisabled();

  release();
  await expect(page.getByText("저장했어요.", { exact: true })).toBeVisible();
  await expect(savePersonaButton(page)).not.toHaveAttribute("aria-busy", "true");
});

test("8000자를 넘겨 적는다", async ({ page, personaAgent }) => {
  await page.goto(`/agents/${personaAgent.code}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("가".repeat(8005));

  await expect(page.getByText("남은 -5자")).toBeVisible();
  await expect(savePersonaButton(page)).toBeDisabled();
});

test("고칠 수 없는 에이전트를 연다", async ({ context, page, personaAgent }, testInfo) => {
  expect((await page.request.patch(`/api/agents/${personaAgent.code}/visibility`, {
    data: { visibility: "GROUP" },
  })).ok()).toBeTruthy();
  await setSession(context, isolatedUser(testInfo, "persona-reader"));
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  const listed = await page.request.get("/api/agents");
  expect(listed.ok()).toBeTruthy();
  expect(await listed.json()).toEqual(expect.arrayContaining([
    expect.objectContaining({ code: personaAgent.code, name: "성격 비서", editable: false }),
  ]));
  await page.goto(`/agents/${personaAgent.code}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await expect(textarea).toBeVisible();
  await expect(textarea).toHaveAttribute("readonly", "");
  await expect(savePersonaButton(page)).toHaveCount(0);
});

const emptyPersonaTest = test.extend({ initialPersona: null });

emptyPersonaTest("본문이 비어 있는 에이전트를 연다", async ({ page, personaAgent }) => {
  await page.goto(`/agents/${personaAgent.code}`);
  await expect(page.getByText("아직 성격을 쓰지 않았어요.")).toBeVisible();
});
