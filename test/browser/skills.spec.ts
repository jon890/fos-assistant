import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import {
  expect,
  PERSONA_AGENT_CODE,
  PERSONA_GROUP_AGENT_CODE,
  setAgentVisibility,
  setSession,
  test,
} from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";

const NAME = "weekly-plan";

function skillMd(name: string, description: string): string {
  return `---\nname: ${name}\ndescription: ${description}\n---\n# 주간 계획\n\n요일별로 할 일을 나눠요.\n`;
}

function skillsSection(page: Page) {
  return page.getByRole("region", { name: "스킬", exact: true });
}

function skillRow(page: Page, name: string) {
  return skillsSection(page).locator("li").filter({ hasText: name });
}

async function putSkill(page: Page, code: string, name: string, description: string) {
  const response = await page.request.put(`/api/agents/${code}/skills/${name}`, {
    data: { skillMd: skillMd(name, description), files: [] },
  });
  if (!response.ok()) throw new Error(`스킬을 올리지 못했다: ${name} ${response.status()} ${await response.text()}`);
}

/** 검사가 남긴 스킬을 지운다. 없으면 그대로 둔다. */
async function removeSkill(page: Page, code: string, name: string) {
  await page.request.delete(`/api/agents/${code}/skills/${name}`);
}

test("주인이 스킬을 쓰고 참고 파일을 올려 저장하고, 고쳐도 참고 파일이 남으며, 지우면 사라진다", async ({ page }) => {
  try {
    await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
    await skillsSection(page).getByRole("link", { name: "스킬 추가" }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/${PERSONA_AGENT_CODE}/skills/new$`));

    await page.getByLabel("스킬 이름").fill(NAME);
    await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd(NAME, "이번 주 계획을 세워요"));
    await page.getByLabel("참고 파일 올리기").setInputFiles({
      name: "guide.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("월요일은 장보기"),
    });
    await expect(page.getByRole("region", { name: "참고 파일" }).getByText("guide.md")).toBeVisible();
    await page.getByRole("button", { name: "저장", exact: true }).click();

    await expect(page).toHaveURL(new RegExp(`/agents/${PERSONA_AGENT_CODE}$`));
    const row = skillRow(page, NAME);
    await expect(row.getByText("올린 스킬")).toBeVisible();
    await expect(row.getByText("이번 주 계획을 세워요")).toBeVisible();

    // 설명만 고치고 저장한다. 손대지 않은 참고 파일은 내용을 다시 보내지 않고 목록에 남아야 한다.
    await row.getByRole("link", { name: `${NAME} 편집` }).click();
    await expect(page.getByRole("region", { name: "참고 파일" }).getByText("references/guide.md")).toBeVisible();
    await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd(NAME, "다음 주 계획을 세워요"));
    await page.getByRole("button", { name: "저장", exact: true }).click();

    await expect(page).toHaveURL(new RegExp(`/agents/${PERSONA_AGENT_CODE}$`));
    await expect(skillRow(page, NAME).getByText("다음 주 계획을 세워요")).toBeVisible();
    const saved = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/${NAME}`);
    const detail = (await saved.json()) as { files: { path: string; size: number }[] };
    expect(detail.files).toEqual([{ path: "references/guide.md", size: Buffer.byteLength("월요일은 장보기") }]);

    await skillRow(page, NAME).getByRole("link", { name: `${NAME} 편집` }).click();
    await expect(page.getByRole("region", { name: "참고 파일" }).getByText("references/guide.md")).toBeVisible();
    await page.getByRole("link", { name: "취소", exact: true }).click();

    await skillRow(page, NAME).getByRole("button", { name: `${NAME} 삭제` }).click();
    const dialog = page.getByRole("alertdialog", { name: `${NAME} 스킬을 지울까요?` });
    await dialog.getByRole("button", { name: "지우기" }).click();
    await expect(dialog).toBeHidden();
    await expect(skillRow(page, NAME)).toHaveCount(0);
  } finally {
    await removeSkill(page, PERSONA_AGENT_CODE, NAME);
  }
});

test("앞머리 이름이 스킬 이름과 다르면 저장하지 않고 오류를 보인다", async ({ page }) => {
  try {
    await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
    await page.getByLabel("스킬 이름").fill("name-mismatch");
    await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd("another-name", "이름이 달라요"));
    await page.getByRole("button", { name: "저장", exact: true }).click();

    await expect(page.getByRole("alert").filter({ hasText: "frontmatter name must equal the skill name" })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/skills/new$`));
    const missing = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/name-mismatch`);
    expect(missing.status()).toBe(404);
  } finally {
    await removeSkill(page, PERSONA_AGENT_CODE, "name-mismatch");
  }
});

test("새 스킬 화면에서 이미 올린 이름을 쓰면 덮어쓰지 않고 오류를 보인다", async ({ page }) => {
  const name = "existing-skill";
  try {
    await putSkill(page, PERSONA_AGENT_CODE, name, "먼저 올린 설명");
    await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
    await page.getByLabel("스킬 이름").fill(name);
    await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd(name, "덮어쓰려는 설명"));
    await page.getByRole("button", { name: "저장", exact: true }).click();

    await expect(page.getByRole("alert").filter({ hasText: "이미 같은 이름의 스킬이 있어요" })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/skills/new$`));
    const kept = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
    expect(((await kept.json()) as { description: string }).description).toBe("먼저 올린 설명");
  } finally {
    await removeSkill(page, PERSONA_AGENT_CODE, name);
  }
});

test("Hermes 기본 스킬과 같은 이름은 저장하지 않고 오류를 보인다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
  await page.getByLabel("스킬 이름").fill("hermes-help");
  await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd("hermes-help", "겹치는 이름"));
  await page.getByRole("button", { name: "저장", exact: true }).click();

  await expect(page.getByRole("alert").filter({ hasText: "같은 이름의 기본 스킬이 있어요." })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/skills/new$`));
});

test("텍스트가 아닌 파일은 참고 파일로 올리지 못한다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
  await page.getByLabel("참고 파일 올리기").setInputFiles({
    name: "photo.png",
    mimeType: "image/png",
    buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00]),
  });

  await expect(page.getByRole("alert").filter({ hasText: "photo.png: 텍스트 파일만 올릴 수 있어요." })).toBeVisible();
  await expect(page.getByRole("region", { name: "참고 파일" }).getByText("아직 참고 파일이 없어요.")).toBeVisible();
});

test("올린 참고 파일의 위치를 templates 로 바꿔 저장한다", async ({ page }) => {
  const name = "template-skill";
  try {
    await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
    await page.getByLabel("스킬 이름").fill(name);
    await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(skillMd(name, "양식을 쓰는 스킬"));
    await page.getByLabel("참고 파일 올리기").setInputFiles({
      name: "form.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("양식"),
    });
    await page.getByLabel("form.txt 위치").selectOption("templates");
    await page.getByRole("button", { name: "저장", exact: true }).click();

    await expect(page).toHaveURL(new RegExp(`/agents/${PERSONA_AGENT_CODE}$`));
    const saved = await page.request.get(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
    expect(((await saved.json()) as { files: { path: string }[] }).files.map((file) => file.path)).toEqual([
      "templates/form.txt",
    ]);
  } finally {
    await removeSkill(page, PERSONA_AGENT_CODE, name);
  }
});

test("미리보기는 앞머리를 빼고 마크다운으로 그리며 들어온 HTML 은 그리지 않는다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}/skills/new`);
  await page.getByRole("textbox", { name: "SKILL.md 본문" }).fill(
    "---\nname: preview-skill\ndescription: 미리보기 설명\n---\n# 미리보기 제목\n\n<b>굵게</b> 글\n",
  );
  await page.getByRole("button", { name: "미리보기" }).click();

  const preview = page.getByRole("region", { name: "SKILL.md 미리보기" });
  await expect(preview.getByRole("heading", { name: "미리보기 제목" })).toBeVisible();
  await expect(preview.getByText("미리보기 설명")).toHaveCount(0);
  await expect(preview.locator("b")).toHaveCount(0);

  await page.getByRole("button", { name: "편집" }).click();
  await expect(page.getByRole("textbox", { name: "SKILL.md 본문" })).toHaveValue(/name: preview-skill/);
});

test("관리하는 사람이 Hermes 기본 스킬을 끄고 켠다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  const row = skillRow(page, "hermes-help");
  await expect(row.getByText("Hermes 기본")).toBeVisible();
  // Hermes 기본 스킬에는 편집과 삭제가 없다.
  await expect(row.getByRole("link", { name: /편집/ })).toHaveCount(0);
  await expect(row.getByRole("button", { name: /삭제/ })).toHaveCount(0);
  try {
    await row.getByRole("button", { name: "켜짐" }).click();
    await expect(row.getByRole("button", { name: "꺼짐" })).toBeVisible();
    await page.reload();
    await expect(skillRow(page, "hermes-help").getByRole("button", { name: "꺼짐" })).toBeVisible();
  } finally {
    const restore = await page.request.put(`/api/agents/${PERSONA_AGENT_CODE}/skills/hermes-help/enabled`, {
      data: { enabled: true },
    });
    expect(restore.ok()).toBeTruthy();
  }
});

test("가족용 에이전트를 다른 사용자가 열면 목록과 부르는 방법만 있고 편집 단추가 없다", async ({ context, page }) => {
  const name = "shared-skill";
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await putSkill(page, PERSONA_GROUP_AGENT_CODE, name, "함께 쓰는 스킬");
  await setAgentVisibility(PERSONA_GROUP_AGENT_CODE, "GROUP", null);
  try {
    await setSession(context, { email: "member@example.com", name: "가족 사용자" });
    await page.goto(`/agents/${PERSONA_GROUP_AGENT_CODE}`);

    const section = skillsSection(page);
    await expect(skillRow(page, name).getByText("함께 쓰는 스킬")).toBeVisible();
    await expect(section.getByText("으로 부를 수 있어요.")).toBeVisible();
    await expect(section.getByRole("link", { name: "스킬 추가" })).toHaveCount(0);
    await expect(section.getByRole("link", { name: /편집/ })).toHaveCount(0);
    await expect(section.getByRole("button")).toHaveCount(0);
  } finally {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await removeSkill(page, PERSONA_GROUP_AGENT_CODE, name);
    await setAgentVisibility(PERSONA_GROUP_AGENT_CODE, "PRIVATE", TEST_EMAIL);
  }
});
