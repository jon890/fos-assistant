import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, FLOW_AGENT_CODE, PERSONA_AGENT_CODE, test } from "./fixtures.ts";

/** 스킬을 올려 둘 에이전트다. 스킬 화면 검사가 쓰는 에이전트와 같아 다른 에이전트의 도구 상태를 바꾸지 않는다. */
const SKILL_AGENT_CODE = PERSONA_AGENT_CODE;
const SKILL_AGENT_NAME = "성격 비서";
const NAME = "weekly-plan";

function composer(page: Page) {
  return page.getByRole("textbox", { name: "메시지" });
}

function skillList(page: Page) {
  return page.getByRole("listbox", { name: "스킬 고르기" });
}

async function putSkill(page: Page, code: string, name: string) {
  const response = await page.request.put(`/api/agents/${code}/skills/${name}`, {
    data: { skillMd: `---\nname: ${name}\ndescription: 이번 주 계획을 세워요\n---\n# 주간 계획\n`, files: [] },
  });
  if (!response.ok()) throw new Error(`스킬을 올리지 못했다: ${name} ${response.status()} ${await response.text()}`);
}

/** 새 대화 화면을 열고 에이전트 카드를 고른다. */
async function startWith(page: Page, agentName: string) {
  await page.goto("/");
  await page.getByRole("radio", { name: agentName, exact: true }).click();
  await expect(page.getByRole("radio", { name: agentName, exact: true })).toHaveAttribute("aria-checked", "true");
}

/** 그 에이전트의 스킬 목록을 이 본문으로 바꿔 돌려준다. */
async function routeSkills(page: Page, body: { skills: { name: string; enabled: boolean }[]; skillsToolsetEnabled: boolean }) {
  await page.route("**/api/agents/*/skills", async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        skills: body.skills.map((skill) => ({ ...skill, description: "", source: "UPLOADED" })),
        editable: true,
        skillsToolsetEnabled: body.skillsToolsetEnabled,
      }),
    });
  });
}

test.describe("스킬을 올린 에이전트", () => {
  test.beforeEach(async ({ page }) => {
    await putSkill(page, SKILL_AGENT_CODE, NAME);
  });

  test.afterEach(async ({ page }) => {
    await page.request.delete(`/api/agents/${SKILL_AGENT_CODE}/skills/${NAME}`);
  });

  test("맨 앞 / 에서 켜진 스킬 목록이 뜨고 글자로 걸러지며 Esc 는 목록만 닫고 Enter 로 /이름 을 넣는다", async ({ page }) => {
    await startWith(page, SKILL_AGENT_NAME);
    const input = composer(page);

    await input.fill("/");
    const list = skillList(page);
    await expect(list.getByRole("option", { name: `/${NAME}` })).toBeVisible();
    // Hermes 기본 스킬 가운데 커맨드 이름 규칙에 맞는 것은 보이고, 점과 밑줄이 든 것은 빠진다.
    await expect(list.getByRole("option", { name: "/hermes-help" })).toBeVisible();
    await expect(list.getByRole("option", { name: /note_taking/ })).toHaveCount(0);

    await input.fill("/week");
    await expect(list.getByRole("option")).toHaveText([`/${NAME}`]);

    await input.press("Escape");
    await expect(list).toHaveCount(0);
    await expect(input).toHaveValue("/week");
    // 닫은 뒤 같은 낱말을 더 쳐도 다시 뜨지 않는다.
    await input.press("l");
    await expect(input).toHaveValue("/weekl");
    await expect(list).toHaveCount(0);

    await input.fill("");
    await input.fill("/wee");
    await expect(list.getByRole("option", { name: `/${NAME}` })).toBeVisible();
    await input.press("Enter");
    await expect(input).toHaveValue(`/${NAME} `);
    await expect(list).toHaveCount(0);
    // 고르기만 했으므로 아직 보내지 않았다.
    await expect(page.getByTestId("user-message")).toHaveCount(0);
  });

  test("커맨드로 보낸 말풍선에 스킬 칩이 붙고 답이 끝난다", async ({ page }) => {
    await startWith(page, SKILL_AGENT_NAME);
    const input = composer(page);

    await input.fill(`/${NAME} 이번 주 계획`);
    await input.press("Enter");

    const userMessage = page.getByTestId("user-message").last();
    await expect(userMessage.getByTestId("skill-chip")).toHaveText(`/${NAME}`);
    await expect(userMessage.locator("p")).toContainText("이번 주 계획");
    await expect(page.getByRole("button", { name: "답 다시 만들기" })).toBeVisible();
    await expect(input).toHaveValue("");
    await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  });

  test("커맨드로 보낸 질문을 다시 생성할 때 스킬이 없으면 영어 원문 대신 해요체 문구를 보인다", async ({ page }) => {
    await startWith(page, SKILL_AGENT_NAME);
    const input = composer(page);
    await input.fill(`/${NAME} 이번 주 계획`);
    await input.press("Enter");
    const regenerate = page.getByTestId("assistant-message").last().getByRole("button", { name: "답 다시 만들기", exact: true });
    await expect(regenerate).toBeVisible();

    // 그사이 스킬이 꺼진 것처럼 다시 생성의 스트림이 `started` 전에 거절한다. backend 가 보내는 사건과 같은 모양이다.
    await page.route("**/api/chat/conversations/*/regenerate", (route) => route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body: 'data: {"type":"error","code":"SKILL_COMMAND_UNKNOWN","message":"this agent has no enabled skill with that name"}\n\n',
    }));
    await regenerate.click();

    await expect(page.getByTestId("turn-error"))
      .toContainText("이 스킬을 이 에이전트에서 쓸 수 없어요. 스킬이 꺼졌거나 지워졌는지 확인해 주세요.");
    await expect(page.getByText("no enabled skill")).toHaveCount(0);
    // 다시 생성은 입력창 아래 알림으로 바꾸지 않는다.
    await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  });

  test("없는 이름은 입력창 아래에 알리고 글을 남기며, 다음 보내기에서 지운다. /usr/bin 은 칩 없이 보낸다", async ({ page }) => {
    await startWith(page, SKILL_AGENT_NAME);
    const input = composer(page);

    await input.fill("/nope 해 줘");
    await input.press("Enter");

    const notice = page.getByTestId("skill-command-notice");
    await expect(notice).toHaveText("/nope 스킬이 이 에이전트에 없어요");
    await expect(input).toHaveValue("/nope 해 줘");
    await expect(page.getByTestId("user-message")).toHaveCount(0);
    await expect(page.getByText("no enabled skill")).toHaveCount(0);
    const inputBox = await page.getByTestId("composer-shell").boundingBox();
    const noticeBox = await notice.boundingBox();
    expect(inputBox, "입력창의 자리를 읽지 못했다").not.toBeNull();
    expect(noticeBox, "알림의 자리를 읽지 못했다").not.toBeNull();
    expect(noticeBox!.y, "알림이 입력창 아래에 있지 않다").toBeGreaterThanOrEqual(inputBox!.y + inputBox!.height);

    await input.fill("/usr/bin 은 뭐야");
    await input.press("Enter");
    await expect(notice).toHaveCount(0);
    const userMessage = page.getByTestId("user-message").last();
    await expect(userMessage.locator("p")).toHaveText("/usr/bin 은 뭐야");
    await expect(userMessage.getByTestId("skill-chip")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "답 다시 만들기" })).toBeVisible();
  });
});

test("스트림 없이 보내도 없는 이름을 같은 문구로 입력창 아래에 알린다", async ({ page }) => {
  // 스트림 경로가 없는 것처럼 404 를 돌려 `/api/chat` 으로 되돌아가게 한다. 거절은 실제 Control Plane 이 한다.
  await page.route("**/api/chat/stream", (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND", message: "" }) }));
  await page.goto("/");
  const input = composer(page);
  await input.fill("/nope 해 줘");
  await input.press("Enter");
  await expect(page.getByTestId("skill-command-notice")).toHaveText("/nope 스킬이 이 에이전트에 없어요");
  await expect(input).toHaveValue("/nope 해 줘");
});

test("스트림이 HTTP 오류로 거절해도 없는 이름을 같은 문구로 입력창 아래에 알린다", async ({ page }) => {
  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 400,
    contentType: "application/json",
    body: JSON.stringify({ code: "SKILL_COMMAND_UNKNOWN", message: "this agent has no enabled skill with that name" }),
  }));
  await page.goto("/");
  const input = composer(page);
  await input.fill("/nope");
  await input.press("Enter");
  await expect(page.getByTestId("skill-command-notice")).toHaveText("/nope 스킬이 이 에이전트에 없어요");
  await expect(input).toHaveValue("/nope");
  await expect(page.getByText("no enabled skill")).toHaveCount(0);
});

test("스킬 목록이 빈 에이전트에서 / 를 치면 스킬이 없다고 알린다", async ({ page }) => {
  await routeSkills(page, { skills: [], skillsToolsetEnabled: true });
  await page.goto("/");
  await composer(page).fill("/");
  await expect(skillList(page)).toHaveText("이 에이전트에는 스킬이 없어요");
});

test("skills 도구가 꺼진 에이전트에서 / 를 치면 켜진 스킬이 있어도 스킬이 없다고 알린다", async ({ page }) => {
  await routeSkills(page, { skills: [{ name: "hermes-help", enabled: true }], skillsToolsetEnabled: false });
  await page.goto("/");
  await composer(page).fill("/");
  await expect(skillList(page)).toHaveText("이 에이전트에는 스킬이 없어요");
});

test("흐름이 붙은 에이전트에서는 스킬 목록을 읽지 않고 / 목록을 띄우지 않는다", async ({ page }) => {
  const skillReads: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes(`/api/agents/${FLOW_AGENT_CODE}/skills`)) skillReads.push(request.url());
  });
  await startWith(page, "흐름 비서");
  await composer(page).fill("/");
  // 사진 단추가 없어진 것으로 흐름 에이전트가 반영된 것을 확인한 뒤에 목록이 없는지 본다.
  await expect(page.getByRole("button", { name: "사진 첨부" })).toHaveCount(0);
  await expect(skillList(page)).toHaveCount(0);
  expect(skillReads).toEqual([]);
});
