import {
  expect,
  setAgentVisibility,
  setSession,
  test,
  PERSONA_AGENT_CODE,
  PERSONA_EMPTY_AGENT_CODE,
  PERSONA_GROUP_AGENT_CODE,
} from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";

test("자기 에이전트의 성격 화면을 연다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  await expect(page.getByRole("button", { name: "저장", exact: true })).toBeVisible();
});

test("성격을 저장한다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("차분하고 다정하게 설명하는 성격이다");
  await page.getByRole("button", { name: "저장", exact: true }).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "저장" }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText("저장했어요.", { exact: true })).toBeVisible();
});

test("확인에서 취소한다", async ({ page }) => {
  const saveRequests: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && request.url().includes(`/api/agents/${PERSONA_AGENT_CODE}/persona`)) {
      saveRequests.push(request.url());
    }
  });

  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("저장하면 안 되는 성격이다");
  await page.getByRole("button", { name: "저장", exact: true }).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  expect(saveRequests).toHaveLength(0);
});

test("확인 창에서 Esc 를 누르면 창이 닫히고 저장하지 않는다", async ({ page }) => {
  const saveRequests: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && request.url().includes(`/api/agents/${PERSONA_AGENT_CODE}/persona`)) {
      saveRequests.push(request.url());
    }
  });

  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("Esc 로 물린 성격이다");
  await page.getByRole("button", { name: "저장", exact: true }).click();

  const dialog = page.getByRole("alertdialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  // 쓰던 글은 편집창에 그대로 남고 저장 요청은 나가지 않는다.
  await expect(textarea).toHaveValue("Esc 로 물린 성격이다");
  expect(saveRequests).toHaveLength(0);
});

test("저장 요청이 도는 동안 저장 단추가 지금 하는 일을 보이고 끝나면 저장되었다고 알린다", async ({ page }) => {
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => { release = resolve; });
  let saveSeen = false;
  await page.route(`**/api/agents/${PERSONA_AGENT_CODE}/persona`, async (route) => {
    if (route.request().method() !== "PUT") return route.continue();
    saveSeen = true;
    await held;
    await route.continue();
  });

  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  await page.getByRole("textbox", { name: "성격 비서 성격" }).fill("기다리는 동안을 보이는 성격이다");
  await page.getByRole("button", { name: "저장", exact: true }).click();
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
  await expect(page.getByRole("button", { name: "저장", exact: true })).not.toHaveAttribute("aria-busy", "true");
});

test("8000자를 넘겨 적는다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("가".repeat(8005));

  await expect(page.getByText("남은 -5자")).toBeVisible();
  await expect(page.getByRole("button", { name: "저장", exact: true })).toBeDisabled();
});

test("고칠 수 없는 에이전트를 연다", async ({ context, page }) => {
  // 이 검사 동안만 그룹 공개로 두고 끝나면 되돌린다. 다른 검사(예: identity.spec.ts)가 관리 화면에
  // 그룹 공개 에이전트가 정확히 하나라고 가정하고 있어, 여기서 하나를 더 남겨 두면 그 검사가 어긋난다.
  await setAgentVisibility(PERSONA_GROUP_AGENT_CODE, "GROUP", null);
  try {
    await setSession(context, { email: "member@example.com", name: "가족 사용자" });
    await page.goto(`/agents/${PERSONA_GROUP_AGENT_CODE}`);

    const textarea = page.getByRole("textbox", { name: "그룹 성격 비서 성격" });
    await expect(textarea).toBeVisible();
    await expect(textarea).toHaveAttribute("readonly", "");
    await expect(page.getByRole("button", { name: "저장", exact: true })).toHaveCount(0);
  } finally {
    await setAgentVisibility(PERSONA_GROUP_AGENT_CODE, "PRIVATE", TEST_EMAIL);
  }
});

test("본문이 비어 있는 에이전트를 연다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_EMPTY_AGENT_CODE}`);
  await expect(page.getByText("아직 성격을 쓰지 않았어요.")).toBeVisible();
});
