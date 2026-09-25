import {
  expect,
  setAgentVisibility,
  setSession,
  test,
  PERSONA_AGENT_CODE,
  PERSONA_EMPTY_AGENT_CODE,
  PERSONA_FAMILY_AGENT_CODE,
} from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";

test("자기 에이전트의 성격 화면을 연다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  await expect(page.getByRole("button", { name: "저장" })).toBeVisible();
});

test("성격을 저장한다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("차분하고 다정하게 설명하는 성격이다");
  await page.getByRole("button", { name: "저장" }).click();

  const dialog = page.getByRole("dialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "저장한다" }).click();

  await expect(dialog).toBeHidden();
  await expect(page.getByText("저장되었습니다")).toBeVisible();
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
  await page.getByRole("button", { name: "저장" }).click();

  const dialog = page.getByRole("dialog", { name: "성격 비서의 성격을 저장할까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  expect(saveRequests).toHaveLength(0);
});

test("8000자를 넘겨 적는다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const textarea = page.getByRole("textbox", { name: "성격 비서 성격" });
  await textarea.fill("가".repeat(8005));

  await expect(page.getByText("남은 -5자")).toBeVisible();
  await expect(page.getByRole("button", { name: "저장" })).toBeDisabled();
});

test("고칠 수 없는 에이전트를 연다", async ({ context, page }) => {
  // 이 검사 동안만 가족 공개로 두고 끝나면 되돌린다. 다른 검사(예: identity.spec.ts)가 관리 화면에
  // 가족 공개 에이전트가 정확히 하나라고 가정하고 있어, 여기서 하나를 더 남겨 두면 그 검사가 어긋난다.
  await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "FAMILY", null);
  try {
    await setSession(context, { email: "member@example.com", name: "가족 사용자" });
    await page.goto(`/agents/${PERSONA_FAMILY_AGENT_CODE}`);

    const textarea = page.getByRole("textbox", { name: "가족 성격 비서 성격" });
    await expect(textarea).toBeVisible();
    await expect(textarea).toHaveAttribute("readonly", "");
    await expect(page.getByRole("button", { name: "저장" })).toHaveCount(0);
  } finally {
    await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "PRIVATE", TEST_EMAIL);
  }
});

test("본문이 비어 있는 에이전트를 연다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_EMPTY_AGENT_CODE}`);
  await expect(page.getByText("아직 성격을 쓰지 않았습니다.")).toBeVisible();
});
