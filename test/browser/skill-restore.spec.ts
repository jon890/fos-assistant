import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";

const FIRST = "이번 주 계획을 세워요";
const SECOND = "다음 주 계획을 세워요";

function skillMd(name: string, description: string): string {
  return `---\nname: ${name}\ndescription: ${description}\n---\n# 주간 계획\n\n요일별로 할 일을 나눠요.\n`;
}

async function putSkill(page: Page, name: string, description: string) {
  const response = await page.request.put(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`, {
    data: { skillMd: skillMd(name, description), files: [] },
  });
  if (!response.ok()) throw new Error(`스킬을 올리지 못했다: ${name} ${response.status()} ${await response.text()}`);
}

/** 테스트가 남긴 스킬을 지운다. 지우면 이전 버전도 함께 지워진다. 없으면 그대로 둔다. */
async function removeSkill(page: Page, name: string) {
  await page.request.delete(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
}

/** 확인 창에서 되돌리고, 창이 닫힐 때까지 기다린다. */
async function restorePrevious(page: Page) {
  await page.getByRole("button", { name: "이전 버전으로", exact: true }).click();
  const dialog = page.getByRole("alertdialog", { name: "이전 버전으로 되돌릴까요?" });
  await expect(dialog.getByText("저장하지 않은 편집은 사라져요.", { exact: false })).toBeVisible();
  await dialog.getByRole("button", { name: "되돌리기", exact: true }).click();
  await expect(dialog).toBeHidden();
}

test("두 번 저장한 스킬은 편집 화면에서 이전 버전으로 되돌리고, 한 번 더 되돌리면 다시 돌아온다", async ({ page }) => {
  const name = "restore-twice";
  try {
    await putSkill(page, name, FIRST);
    await putSkill(page, name, SECOND);
    await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/${name}`);

    const editor = page.getByRole("textbox", { name: "SKILL.md 본문" });
    await expect(editor).toHaveValue(new RegExp(`description: ${SECOND}`));
    await expect(page.getByText(/^이전 버전: /)).toBeVisible();

    await restorePrevious(page);
    await expect(editor).toHaveValue(new RegExp(`description: ${FIRST}`));
    const restored = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
    expect(((await restored.json()) as { description: string }).description).toBe(FIRST);

    await restorePrevious(page);
    await expect(editor).toHaveValue(new RegExp(`description: ${SECOND}`));
    const swappedBack = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
    expect(((await swappedBack.json()) as { description: string }).description).toBe(SECOND);
  } finally {
    await removeSkill(page, name);
  }
});

test("한 번만 저장한 스킬의 편집 화면에는 「이전 버전으로」 가 없다", async ({ page }) => {
  const name = "restore-once";
  try {
    await putSkill(page, name, FIRST);
    await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/${name}`);

    await expect(page.getByRole("textbox", { name: "SKILL.md 본문" })).toHaveValue(new RegExp(`description: ${FIRST}`));
    await expect(page.getByRole("button", { name: "이전 버전으로", exact: true })).toHaveCount(0);
    await expect(page.getByText(/^이전 버전: /)).toHaveCount(0);
  } finally {
    await removeSkill(page, name);
  }
});
