import type { Page } from "../../node_modules/@playwright/test/index.js";
import {
  bindDemoConnector, connectDemoConnector, disconnectDemoConnector,
  expect, PERSONA_AGENT_CODE, setSession, test,
} from "./fixtures.ts";
import { openConversationWith, restorePrimaries } from "./helpers.ts";

/** 스킬을 올려 둘 에이전트다. 스킬 화면 검사가 쓰는 에이전트와 같아 다른 에이전트의 도구 상태를 바꾸지 않는다. */
const SKILL_AGENT_CODE = PERSONA_AGENT_CODE;
const NAME = "weekly-plan";
const OTHER_NAME = "shopping-plan";
const DISABLED_NAME = "weekly-plan-off";

function composer(page: Page) {
  return page.getByRole("textbox", { name: "메시지" });
}

function skillList(page: Page) {
  return page.getByRole("listbox", { name: "스킬 고르기" });
}

async function putSkill(page: Page, code: string, name: string) {
  const response = await page.request.put(
    `/api/agents/${code}/skills/${name}`,
    {
      data: {
        skillMd: `---\nname: ${name}\ndescription: 이번 주 계획을 세워요\n---\n# 주간 계획\n`,
        files: [],
      },
    },
  );
  if (!response.ok())
    throw new Error(
      `스킬을 올리지 못했다: ${name} ${response.status()} ${await response.text()}`,
    );
}

/** 그 에이전트와의 대화를 연다. 새 대화는 기본 비서로만 열려 다른 에이전트는 지난 대화로 연다(스펙 assistant-hub REQ4). */
async function startWith(page: Page, agentCode: string, email?: string) {
  await openConversationWith(page, agentCode, email);
}

/**
 * 그 에이전트의 스킬 목록을 이 본문으로 바꿔 돌려준다. 비서 대화의 `/` 목록에 붙는 맡길 스킬(스펙 assistant-hub REQ10)은 비워 이
 * 에이전트의 목록만 보이게 한다.
 */
async function routeSkills(
  page: Page,
  body: {
    skills: { name: string; enabled: boolean; source?: "CONNECTOR" }[];
    skillsToolsetEnabled: boolean;
  },
) {
  await page.route("**/api/chat/skill-delegations/targets", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.route("**/api/agents/*/skills", async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        skills: body.skills.map((skill) => ({
          description: "",
          source: "UPLOADED",
          ...skill,
        })),
        editable: true,
        skillsToolsetEnabled: body.skillsToolsetEnabled,
      }),
    });
  });
}

test("붙인 플러그인의 스킬이 슬래시 목록과 읽기 전용 설정에 보인다", async ({
  context, page, isolatedMember,
}) => {
  const name = "메모 검토 비서";
  await setSession(context, isolatedMember);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  await connectDemoConnector(isolatedMember.email);
  let code: string | undefined;
  try {
    const created = await page.request.post("/api/agents", { data: { name } });
    expect(created.status()).toBe(201);
    code = ((await created.json()) as { code: string }).code;
    // 스킬 도구를 켠 뒤 업로드를 지워 플러그인 스킬만 남긴다.
    await putSkill(page, code, NAME);
    expect((await page.request.delete(`/api/agents/${code}/skills/${NAME}`)).ok()).toBeTruthy();
    await bindDemoConnector(isolatedMember.email, code);

    await startWith(page, code, isolatedMember.email);
    await composer(page).fill("/");
    await expect(skillList(page).getByRole("option")).toHaveText(["/demo-note-review"]);

    await page.goto(`/agents/${code}`);
    const item = page.getByRole("region", { name: "스킬" }).getByRole("listitem")
      .filter({ hasText: "demo-note-review" });
    await expect(item).toContainText("검사용 메모");
    await expect(item).toContainText("켜짐");
    await expect(item.getByRole("link")).toHaveCount(0);
    await expect(item.getByRole("button")).toHaveCount(0);
    await expect(item.getByRole("switch")).toHaveCount(0);
  } finally {
    try {
      // test-support 대화 만들기가 잠시 둔 기본 비서는 지울 수 없다(409). 지우기 전에 되돌린다.
      await restorePrimaries();
      if (code) expect((await page.request.delete(`/api/agents/${code}`)).ok()).toBeTruthy();
    } finally {
      await disconnectDemoConnector(isolatedMember.email);
    }
  }
});

test.describe("스킬을 올린 에이전트", () => {
  test.beforeEach(async ({ page }) => {
    await putSkill(page, SKILL_AGENT_CODE, NAME);
  });

  test.afterEach(async ({ page }) => {
    await page.request.delete(`/api/agents/${SKILL_AGENT_CODE}/skills/${NAME}`);
    await page.request.delete(
      `/api/agents/${SKILL_AGENT_CODE}/skills/${OTHER_NAME}`,
    );
    await page.request.delete(
      `/api/agents/${SKILL_AGENT_CODE}/skills/${DISABLED_NAME}`,
    );
  });

  test("맨 앞 / 에서 켜진 스킬 목록이 뜨고 글자로 걸러지며 Esc 는 목록만 닫고 Enter 로 /이름 을 넣는다", async ({
    page,
  }) => {
    await putSkill(page, SKILL_AGENT_CODE, OTHER_NAME);
    await putSkill(page, SKILL_AGENT_CODE, DISABLED_NAME);
    const disabled = await page.request.put(
      `/api/agents/${SKILL_AGENT_CODE}/skills/${DISABLED_NAME}/enabled`,
      {
        data: { enabled: false },
      },
    );
    expect(disabled.ok()).toBeTruthy();
    await startWith(page, SKILL_AGENT_CODE);
    const input = composer(page);

    await input.fill("/");
    const list = skillList(page);
    await expect(list.getByRole("option", { name: `/${NAME}` })).toBeVisible();
    // 일반 API 를 쓰므로 켜진 업로드 스킬만 보이고 기본 스킬과 꺼진 스킬은 빠진다.
    await expect(list.getByRole("option")).toHaveText([
      `/${OTHER_NAME}`,
      `/${NAME}`,
    ]);
    await expect(
      list.getByRole("option", { name: "/hermes-help" }),
    ).toHaveCount(0);
    await expect(list.getByRole("option", { name: /note_taking/ })).toHaveCount(
      0,
    );
    await expect(
      list.getByRole("option", { name: `/${DISABLED_NAME}`, exact: true }),
    ).toHaveCount(0);

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

  test("커맨드로 보낸 말풍선에 스킬 칩이 붙고 답이 끝난다", async ({
    page,
  }) => {
    await startWith(page, SKILL_AGENT_CODE);
    const input = composer(page);

    await input.fill(`/${NAME} 이번 주 계획`);
    await input.press("Enter");

    const userMessage = page.getByTestId("user-message").last();
    await expect(userMessage.getByTestId("skill-chip")).toHaveText(`/${NAME}`);
    await expect(userMessage.locator("p")).toContainText("이번 주 계획");
    await expect(
      page.getByRole("button", { name: "답 다시 만들기" }),
    ).toBeVisible();
    await expect(input).toHaveValue("");
    await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  });

  test("사진을 받지 않는 GROUP 에이전트도 스킬 목록과 보낸 커맨드의 칩을 보인다", async ({
    page,
  }) => {
    // 화면의 공개 범위·사진 수신 값만 바꾼다. 실제 스킬 실행은 올려 둔 같은 에이전트로 왕복한다.
    await page.route("**/api/agents", async (route) => {
      const response = await route.fetch();
      const agents = await response.json();
      await route.fulfill({
        response,
        json: agents.map((agent: { code: string }) =>
          agent.code === SKILL_AGENT_CODE
            ? { ...agent, visibility: "GROUP", acceptsAttachments: false }
            : agent,
        ),
      });
    });
    await startWith(page, SKILL_AGENT_CODE);
    const input = composer(page);
    await input.fill("/");
    await expect(
      skillList(page).getByRole("option", { name: `/${NAME}` }),
    ).toBeVisible();
    await input.fill(`/${NAME} 그룹 계획`);
    await input.press("Enter");
    await expect(
      page.getByTestId("user-message").last().getByTestId("skill-chip"),
    ).toHaveText(`/${NAME}`);
    await expect(
      page.getByRole("button", { name: "답 다시 만들기" }),
    ).toBeVisible();
  });

  test("목록에서 숨긴 기본 스킬도 이름을 직접 입력하면 커맨드로 실행한다", async ({
    page,
  }) => {
    await startWith(page, SKILL_AGENT_CODE);
    const input = composer(page);
    await input.fill("/hermes-help 사용법");
    await input.press("Enter");

    const userMessage = page.getByTestId("user-message").last();
    await expect(userMessage.getByTestId("skill-chip")).toHaveText(
      "/hermes-help",
    );
    await expect(userMessage.locator("p")).toContainText("사용법");
    await expect(
      page.getByRole("button", { name: "답 다시 만들기" }),
    ).toBeVisible();
    await expect(input).toHaveValue("");
    await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  });

  test("커맨드로 보낸 질문을 다시 생성할 때 스킬이 없으면 영어 원문 대신 해요체 문구를 보인다", async ({
    page,
  }) => {
    await startWith(page, SKILL_AGENT_CODE);
    const input = composer(page);
    await input.fill(`/${NAME} 이번 주 계획`);
    await input.press("Enter");
    const regenerate = page
      .getByTestId("assistant-message")
      .last()
      .getByRole("button", { name: "답 다시 만들기", exact: true });
    await expect(regenerate).toBeVisible();

    // 그사이 스킬이 꺼진 것처럼 다시 생성의 스트림이 `started` 전에 거절한다. backend 가 보내는 사건과 같은 모양이다.
    await page.route("**/api/chat/conversations/*/regenerate", (route) =>
      route.fulfill({
        status: 200,
        contentType: "text/event-stream",
        body: 'data: {"type":"error","code":"SKILL_COMMAND_UNKNOWN","message":"this agent has no enabled skill with that name"}\n\n',
      }),
    );
    await regenerate.click();

    await expect(page.getByTestId("turn-error")).toContainText(
      "이 스킬을 이 에이전트에서 쓸 수 없어요. 스킬이 꺼졌거나 지워졌는지 확인해 주세요.",
    );
    await expect(page.getByText("no enabled skill")).toHaveCount(0);
    // 다시 생성은 입력창 아래 알림으로 바꾸지 않는다.
    await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  });

  test("없는 이름은 입력창 아래에 알리고 글을 남기며, 다음 보내기에서 지운다. /usr/bin 은 칩 없이 보낸다", async ({
    page,
  }) => {
    await startWith(page, SKILL_AGENT_CODE);
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
    expect(
      noticeBox!.y,
      "알림이 입력창 아래에 있지 않다",
    ).toBeGreaterThanOrEqual(inputBox!.y + inputBox!.height);

    await input.fill("/usr/bin 은 뭐야");
    await input.press("Enter");
    await expect(notice).toHaveCount(0);
    const userMessage = page.getByTestId("user-message").last();
    await expect(userMessage.locator("p")).toHaveText("/usr/bin 은 뭐야");
    await expect(userMessage.getByTestId("skill-chip")).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "답 다시 만들기" }),
    ).toBeVisible();
  });
});

test("스트림 경로 거절은 비스트림으로 다시 보내지 않고 글을 남긴다", async ({
  page,
}) => {
  let fallbackPosts = 0;
  page.on("request", (request) => {
    if (
      new URL(request.url()).pathname === "/api/chat" &&
      request.method() === "POST"
    )
      fallbackPosts++;
  });
  // 상태 번호만으로 수락 전 실패를 증명하지 못하므로 별도 송신을 하지 않는다.
  await page.route("**/api/chat/stream", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({
        code: "NOT_FOUND",
        message: "응답 연결을 열지 못했어요.",
      }),
    }),
  );
  await page.goto("/new");
  const input = composer(page);
  await input.fill("/nope 해 줘");
  await input.press("Enter");
  await expect(page.getByText("응답 연결을 열지 못했어요.")).toBeVisible();
  await expect(page.getByTestId("skill-command-notice")).toHaveCount(0);
  await expect(input).toHaveValue("/nope 해 줘");
  await expect(page.getByTestId("user-message")).toHaveCount(0);
  await expect(page.getByTestId("assistant-message")).toHaveCount(0);
  expect(fallbackPosts).toBe(0);
});

test("스트림이 HTTP 오류로 거절해도 없는 이름을 같은 문구로 입력창 아래에 알린다", async ({
  page,
}) => {
  await page.route("**/api/chat/stream", (route) =>
    route.fulfill({
      status: 400,
      contentType: "application/json",
      body: JSON.stringify({
        code: "SKILL_COMMAND_UNKNOWN",
        message: "this agent has no enabled skill with that name",
      }),
    }),
  );
  await page.goto("/new");
  const input = composer(page);
  await input.fill("/nope");
  await input.press("Enter");
  await expect(page.getByTestId("skill-command-notice")).toHaveText(
    "/nope 스킬이 이 에이전트에 없어요",
  );
  await expect(input).toHaveValue("/nope");
  await expect(page.getByText("no enabled skill")).toHaveCount(0);
});

test("스킬 목록이 빈 에이전트에서 / 를 치면 스킬이 없다고 알린다", async ({
  page,
}) => {
  await routeSkills(page, { skills: [], skillsToolsetEnabled: true });
  await page.goto("/new");
  const input = composer(page);
  await input.fill("/");
  await expect(skillList(page)).toHaveText("이 에이전트에는 스킬이 없어요");
  // 글자를 더 치면 커맨드가 아닌 글일 수 있어 알림을 닫는다.
  await input.press("u");
  await input.press("s");
  await input.press("r");
  await expect(input).toHaveValue("/usr");
  await expect(skillList(page)).toHaveCount(0);
  // 다시 `/` 만 남기면 알림이 돌아온다.
  await input.fill("/");
  await expect(skillList(page)).toHaveText("이 에이전트에는 스킬이 없어요");
});

test("skills 도구가 꺼진 에이전트에서 / 를 치면 켜진 플러그인 스킬이 있어도 스킬이 없다고 알린다", async ({
  page,
}) => {
  await routeSkills(page, {
    skills: [{ name: "demo-note-review", enabled: true, source: "CONNECTOR" }],
    skillsToolsetEnabled: false,
  });
  await page.goto("/new");
  await composer(page).fill("/");
  await expect(skillList(page)).toHaveText("이 에이전트에는 스킬이 없어요");
});
