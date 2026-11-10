import { crc32 } from "node:zlib";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

const UPLOAD_PATH = new RegExp(`/api/agents/${PERSONA_AGENT_CODE}/skill-packages$`);

function skillMd(name: string, description: string): string {
  return `---\nname: ${name}\ndescription: ${description}\n---\n# 주간 계획\n\n요일별로 할 일을 나눠요.\n`;
}

/**
 * 저장(STORED) 방식 zip 바이트를 만든다. 경로마다 로컬 머리와 내용, 중앙 디렉터리, 끝 레코드를 차례로 잇는다.
 * 날짜는 1980-01-01 로 고정해 실행할 때마다 같은 바이트가 나온다.
 */
function storedZip(entries: Record<string, string>): Buffer {
  const DOS_DATE = (1 << 5) | 1;
  const UTF8_FLAG = 0x0800;
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const [path, text] of Object.entries(entries)) {
    const name = Buffer.from(path, "utf8");
    const data = Buffer.from(text, "utf8");
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(UTF8_FLAG, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(UTF8_FLAG, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt32LE(offset, 42);

    locals.push(local, name, data);
    centrals.push(central, name);
    offset += local.length + name.length + data.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(Object.keys(entries).length, 8);
  end.writeUInt16LE(Object.keys(entries).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

function skillsSection(page: Page) {
  return page.getByRole("region", { name: "스킬", exact: true });
}

function skillRow(page: Page, name: string) {
  return skillsSection(page).locator("li").filter({ hasText: name });
}

function fileRow(page: Page, path: string) {
  return page.getByRole("dialog").getByRole("region", { name: "묶음의 파일" }).locator("li").filter({ hasText: path });
}

async function chooseZip(page: Page, entries: Record<string, string>) {
  await page.getByLabel("스킬 zip 파일").setInputFiles({
    name: "skill.zip",
    mimeType: "application/zip",
    buffer: storedZip(entries),
  });
}

/** 검사가 남긴 스킬을 지운다. 없으면 그대로 둔다. */
async function removeSkill(page: Page, name: string) {
  await page.request.delete(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`);
}

/** 켜진 도구 이름이다. 다른 시험이 쓰는 에이전트라 바꾼 뒤 이 값으로 되돌린다. */
async function enabledToolsets(page: Page): Promise<string[]> {
  const response = await page.request.get(`/api/admin/agents/${PERSONA_AGENT_CODE}/tools`);
  if (!response.ok()) throw new Error(`도구 목록을 읽지 못했다: ${response.status()} ${await response.text()}`);
  const view = (await response.json()) as { toolsets: { name: string; enabled: boolean }[] };
  return view.toolsets.filter((toolset) => toolset.enabled).map((toolset) => toolset.name);
}

async function putToolsets(page: Page, enabled: string[]) {
  const response = await page.request.put(`/api/admin/agents/${PERSONA_AGENT_CODE}/tools`, { data: { enabled } });
  if (!response.ok()) throw new Error(`도구를 바꾸지 못했다: ${enabled.join(",")} ${response.status()} ${await response.text()}`);
}

test("주인이 SKILL.md 와 참고 파일이 든 zip 을 미리보고 올리면 스킬 절에 올린 스킬로 보인다", async ({ page }) => {
  const name = "zip-weekly";
  try {
    await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
    await chooseZip(page, {
      "SKILL.md": skillMd(name, "이번 주 계획을 세워요"),
      "references/guide.md": "월요일은 장보기",
    });

    const dialog = page.getByRole("dialog", { name: `${name} 스킬을 올릴까요?` });
    await expect(dialog).toBeVisible();
    await expect(fileRow(page, "SKILL.md").getByText("새 파일")).toBeVisible();
    await expect(fileRow(page, "references/guide.md").getByText("새 파일")).toBeVisible();
    await expect(dialog.getByText("이번 주 계획을 세워요", { exact: true })).toBeVisible();

    await clickAndWaitForResponse(page, dialog.getByRole("button", { name: "올리기", exact: true }), "POST", UPLOAD_PATH);
    await expect(dialog).toBeHidden();
    await expect(skillRow(page, name).getByText("올린 스킬")).toBeVisible();
    await expect(skillRow(page, name).getByText("이번 주 계획을 세워요")).toBeVisible();
  } finally {
    await removeSkill(page, name);
  }
});

test("같은 이름의 zip 은 바뀐 파일을 보이고 덮어쓰면 편집 화면에 바뀐 내용이 있다", async ({ page }) => {
  const name = "zip-overwrite";
  const body = skillMd(name, "이번 주 계획을 세워요");
  try {
    const created = await page.request.put(`/api/agents/${PERSONA_AGENT_CODE}/skills/${name}`, {
      data: { skillMd: body, files: [{ path: "references/guide.md", content: "월요일은 장보기" }] },
    });
    expect(created.ok(), `스킬을 미리 만들지 못했다: ${created.status()}`).toBeTruthy();

    await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
    await chooseZip(page, {
      "SKILL.md": body,
      "references/guide.md": "화요일은 청소",
      "references/extra.md": "수요일은 운동",
    });

    const dialog = page.getByRole("dialog", { name: `${name} 스킬을 덮어쓸까요?` });
    await expect(dialog).toBeVisible();
    await expect(fileRow(page, "references/guide.md").getByText("바뀜")).toBeVisible();
    await expect(fileRow(page, "references/extra.md").getByText("새 파일")).toBeVisible();
    await expect(fileRow(page, "SKILL.md").getByText("같음")).toBeVisible();

    await clickAndWaitForResponse(page, dialog.getByRole("button", { name: "덮어쓰기", exact: true }), "POST", UPLOAD_PATH);
    await expect(dialog).toBeHidden();

    await skillRow(page, name).getByRole("link", { name: `${name} 편집` }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/${PERSONA_AGENT_CODE}/skills/${name}$`));
    const files = page.getByRole("region", { name: "참고 파일" });
    await expect(files.getByText("references/extra.md", { exact: true })).toBeVisible();
    await files.getByText("references/guide.md 내용 고치기").click();
    await expect(page.getByRole("textbox", { name: "references/guide.md 본문" })).toHaveValue("화요일은 청소");
  } finally {
    await removeSkill(page, name);
  }
});

test("SKILL.md 가 없는 zip 은 까닭을 보이고 올리기 단추를 끈다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
  await chooseZip(page, { "references/guide.md": "월요일은 장보기" });

  const dialog = page.getByRole("dialog", { name: "스킬 묶음을 올릴 수 없어요" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("zip 맨 위에 SKILL.md 가 없어요.")).toBeVisible();
  await expect(dialog.getByRole("button", { name: "올리기", exact: true })).toBeDisabled();
});

test("셸이 꺼진 에이전트에 스크립트가 든 zip 을 고르면 실행 공간 안내를 보이고 올리기 단추를 끈다", async ({ page }) => {
  const name = "zip-script";
  const original = await enabledToolsets(page);
  try {
    if (original.includes("terminal")) await putToolsets(page, original.filter((toolset) => toolset !== "terminal"));

    await page.goto(`/agents/${PERSONA_AGENT_CODE}`);
    await chooseZip(page, {
      "SKILL.md": skillMd(name, "스크립트를 돌려요"),
      "scripts/run.sh": "#!/bin/sh\necho 월요일\n",
    });

    const dialog = page.getByRole("dialog", { name: `${name} 스킬을 올릴까요?` });
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByText("이 에이전트는 실행 공간이 없어 스크립트가 든 스킬을 올릴 수 없어요. 도구에서 셸을 켜 주세요."),
    ).toBeVisible();
    await expect(dialog.getByRole("button", { name: "올리기", exact: true })).toBeDisabled();
  } finally {
    if (original.includes("terminal")) await putToolsets(page, original);
    await removeSkill(page, name);
  }
});
