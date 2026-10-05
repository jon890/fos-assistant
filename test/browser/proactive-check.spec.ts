import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { CONVERSATION_URL, expect, test } from "./fixtures.ts";

const AGENT_CODE = "browser";
const SKILL_NAME = "proactive-check";
const START_NOTICE = "먼저 살펴보기를 시작했어요";

function section(page: Page) {
  return page.getByRole("region", { name: "먼저 살펴보기", exact: true });
}

function skillMd(): string {
  return `---\nname: ${SKILL_NAME}\ndescription: 살펴볼 것을 정해요\n---\n# 먼저 살펴보기\n\n읽기만 해요.\n`;
}

async function putTools(page: Page, enabled: string[]) {
  const response = await page.request.put(`/api/admin/agents/${AGENT_CODE}/tools`, { data: { enabled } });
  if (!response.ok()) throw new Error(`도구를 바꾸지 못했다: ${response.status()} ${await response.text()}`);
}

/** 이전 검사가 남긴 점검 대화를 지운다. 같은 대화에 알린 발견이 남으면 첫 결과가 「이미 알린 것」 으로 내려간다. */
async function deleteCheckConversation(page: Page) {
  const status = await page.request.get(`/api/agents/${AGENT_CODE}/proactive-check`);
  if (!status.ok()) return;
  const { conversationId } = (await status.json()) as { conversationId: string | null };
  if (conversationId) await page.request.delete(`/api/chat/conversations/${conversationId}`);
}

/** 살펴보기 스킬을 올리고 허용된 도구만 켠다. `extraTools` 는 허용 목록 밖의 도구다. */
async function prepare(page: Page, extraTools: string[] = []) {
  await deleteCheckConversation(page);
  const uploaded = await page.request.put(`/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`, {
    data: { skillMd: skillMd(), files: [] },
  });
  if (!uploaded.ok()) throw new Error(`스킬을 올리지 못했다: ${uploaded.status()} ${await uploaded.text()}`);
  await putTools(page, ["skills", ...extraTools]);
}

/** 검사가 바꾼 도구와 스킬을 되돌리고 점검 대화를 지운다. */
async function restore(page: Page) {
  await deleteCheckConversation(page);
  await page.request.delete(`/api/agents/${AGENT_CODE}/skills/${SKILL_NAME}`);
  await putTools(page, []);
}

test("허용되지 않은 도구가 켜져 있으면 까닭을 알리고 단추를 끈다", async ({ page }) => {
  try {
    await prepare(page, ["terminal"]);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    await expect(check.getByRole("button", { name: "지금 살펴보기" })).toBeDisabled();
    await expect(check.getByText("명령 실행 도구가 켜져 있어서 살펴볼 수 없어요.")).toBeVisible();
    await expect(check.getByText("위 도구 절에서 꺼 주세요.", { exact: false })).toBeVisible();
    await expect(check.getByText("점검 대화 열기")).toHaveCount(0);
  } finally {
    await restore(page);
  }
});

test("단추 하나로 살펴보기를 시작하고 결과와 배지를 보며 점검 대화에서 다시 시작한다", async ({ page }, testInfo) => {
  try {
    await prepare(page);
    await page.goto(`/agents/${AGENT_CODE}`);

    const check = section(page);
    await expect(check.getByText("도구가 켜져 있어서")).toHaveCount(0);
    await check.getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);

    // 시작 알림 줄과 결과 답이 이력에 들어온다. 결과의 원문 링크는 하나다.
    await expect(page.getByText(START_NOTICE)).toHaveCount(1);
    await expect(page.getByText("새로 알릴 것", { exact: true })).toBeVisible();
    const source = page.getByRole("link", { name: "example.com" });
    await expect(source).toHaveCount(1);
    await expect(source).toHaveAttribute("href", "https://example.com/e2e/study");

    // 대화 목록의 점검 대화 줄에 「살펴보기」 배지가 붙는다.
    const mobile = testInfo.project.name === "mobile";
    if (mobile) await page.getByRole("button", { name: "사이드바 열기" }).click();
    const row = page
      .getByRole("navigation", { name: "대화 목록" })
      .getByRole("link")
      .filter({ hasText: "먼저 살펴보기 ·" });
    await expect(row.getByText("살펴보기", { exact: true })).toBeVisible();
    if (mobile) await page.getByRole("button", { name: "사이드바 닫기" }).click();

    // 점검 대화의 머리 줄에서 다시 시작한다. 같은 발견은 새로 알릴 것이 아니라 참고로 내려간다.
    await page.getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page.getByText(START_NOTICE)).toHaveCount(2);
    await expect(page.getByText("이미 알린 것이에요", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "지금 살펴보기" })).toBeEnabled();
  } finally {
    await restore(page);
  }
});

test("결과를 읽지 못한 살펴보기는 점검 대화와 마지막 살펴보기 줄에 같은 까닭을 보인다", async ({ page, hermes }) => {
  try {
    await prepare(page);
    await page.goto(`/agents/${AGENT_CODE}`);

    // 답이 비었으면 형식 탓으로 말하지 않는다.
    await hermes.setProactiveOutput("");
    await section(page).getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);
    await expect(page.getByText("살펴봤지만 답을 받지 못했어요. 다시 눌러 주세요")).toBeVisible();
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(section(page).getByText("답을 받지 못했어요", { exact: false })).toBeVisible();

    // 블록이 없으면 형식 문제로 알린다.
    await hermes.setProactiveOutput("블록 없이 끝난 답");
    await section(page).getByRole("button", { name: "지금 살펴보기" }).click();
    await expect(page).toHaveURL(CONVERSATION_URL);
    await expect(
      page.getByText("살펴봤지만 결과 형식이 맞지 않아 정리하지 못했어요. 다시 눌러 주세요"),
    ).toBeVisible();
    await page.goto(`/agents/${AGENT_CODE}`);
    await expect(
      section(page).getByText("결과 형식이 맞지 않아 정리하지 못했어요", { exact: false }),
    ).toBeVisible();
  } finally {
    await restore(page);
  }
});
