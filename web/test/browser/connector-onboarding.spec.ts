import {
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { clickAndWaitForResponse, isolatedUser } from "./helpers.ts";
import { DEMO_TOKEN_OK } from "../../../test/e2e/fake-hermes.ts";
import type {
  Page,
  TestInfo,
} from "../../node_modules/@playwright/test/index.js";
import { BINDING_POLL_MS } from "../../src/lib/binding-poll.ts";

/**
 * 외부 서비스를 연결한 직후 같은 자리에서 쓸 에이전트를 고르고, 붙이고, 그 에이전트와 대화로 잇는 흐름을 본다.
 *
 * <p>검사마다 따로 만든 사용자로 시험 플러그인을 연결하고, 끝나면 만든 에이전트를 지운 뒤 연결을 해제한다.
 */

const TITLE = "검사용 메모";
const CONNECTOR_PATH = "/api/connections/demo-notes";
const PRIVATE_NAME = "연결 쓰는 비서";
const GROUP_NAME = "모두의 비서";
const GROUP_REASON =
  "그룹에 공개한 에이전트라 붙일 수 없어요. 나만 쓰는 에이전트로 바꾸면 붙일 수 있어요.";
const SHELL_RISK_MESSAGE =
  "안전하게 분리되지 않은 에이전트는 연결 정보를 읽고 승인 없이 서비스를 이용할 수 있어요. 분리된 에이전트도 읽은 내용을 인터넷으로 보낼 수 있어요.";

function ownerOf(testInfo: TestInfo) {
  return isolatedUser(testInfo, "onboarding");
}

async function createAgent(
  page: Page,
  name: string,
  visibility?: "GROUP",
): Promise<string> {
  const response = await page.request.post("/api/agents", {
    data: { name, visibility },
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { code: string }).code;
}

/** 연결 하나의 화면에서 토큰을 넣고 연결한다. 등록 응답을 다 받을 때까지 기다린다. */
async function connect(page: Page) {
  await page.getByLabel("토큰", { exact: true }).fill(DEMO_TOKEN_OK);
  await clickAndWaitForResponse(
    page,
    page.getByRole("button", { name: "연결하기" }),
    "POST",
    new RegExp(`^${CONNECTOR_PATH}$`),
  );
}

function offer(page: Page) {
  return page.getByTestId("specialist-agent-offer");
}

/** 처음 연결을 마치면 뜨는 전문 에이전트 제안을 거절하고 지금의 고르기로 간다. */
async function declineOffer(page: Page) {
  await offer(page)
    .getByRole("button", { name: "고른 에이전트에 붙이기" })
    .click();
  await expect(offer(page)).toHaveCount(0);
}

function chooser(page: Page) {
  return page.getByTestId("connector-agent-chooser");
}

function choice(page: Page, name: string) {
  return chooser(page).getByTestId("agent-choice").filter({ hasText: name });
}

test.beforeEach(async ({ context, page }, testInfo) => {
  await setSession(context, ownerOf(testInfo));
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
});

test.afterEach(async ({ context, page }, testInfo) => {
  const owner = ownerOf(testInfo);
  await setSession(context, owner);
  const response = await page.request.get("/api/agents");
  const mine = (
    (await response.json()) as { code: string; ownedByMe: boolean }[]
  ).filter((agent) => agent.ownedByMe);
  for (const agent of mine) {
    const deleted = await page.request.delete(`/api/agents/${agent.code}`);
    expect(
      deleted.status(),
      `검사가 만든 에이전트 ${agent.code} 를 지우지 못했다`,
    ).toBe(204);
  }
  await disconnectDemoConnector(owner.email);
});

test("연결을 마치면 같은 자리에서 고른 에이전트에 붙이고 그 에이전트와 대화로 이어진다", async ({
  page,
}) => {
  const code = await createAgent(page, PRIVATE_NAME);
  const groupCode = await createAgent(page, GROUP_NAME, "GROUP");

  await page.goto("/connections");
  await expect(
    page.getByRole("heading", { name: "외부 서비스 연결", exact: true }),
  ).toBeVisible();
  await page.getByTestId("connector-card").filter({ hasText: TITLE }).click();
  await expect(page).toHaveURL(/\/connections\/demo-notes$/);
  await expect(chooser(page)).toHaveCount(0);
  await connect(page);

  const heading = page.getByRole("heading", {
    name: `${TITLE} 연결이 끝났어요`,
  });
  await expect(heading).toBeFocused();
  await declineOffer(page);
  await expect(heading).toBeFocused();
  await expect(chooser(page)).toContainText("어느 에이전트에서 쓸까요?");
  // 붙일 수 있는 에이전트가 먼저이고, 그룹에 공개한 에이전트는 단추 대신 까닭을 보인다.
  await expect(chooser(page).getByTestId("agent-choice").first()).toContainText(
    PRIVATE_NAME,
  );
  await expect(choice(page, GROUP_NAME)).toContainText(GROUP_REASON);
  await expect(choice(page, GROUP_NAME).getByRole("button")).toHaveCount(0);

  await clickAndWaitForResponse(
    page,
    choice(page, PRIVATE_NAME).getByRole("button", {
      name: "이 에이전트에서 쓰기",
    }),
    "PUT",
    new RegExp(`^/api/agents/${code}/connections/demo-notes$`),
  );
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: `「${PRIVATE_NAME}」에 붙였어요` }),
  ).toBeVisible();
  await expect(page.getByTestId("connector-bound-result")).toContainText(
    `이제 「${PRIVATE_NAME}」 대화에서 「${TITLE}」 도구를 써요. 몇 분 안에 쓸 수 있어요.`,
  );
  // 붙인 에이전트 목록도 다시 읽혀 이 에이전트가 보인다.
  await expect(page.getByTestId("connection-binding")).toContainText(
    PRIVATE_NAME,
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);

  await page.getByRole("link", { name: `${PRIVATE_NAME}와 대화하기` }).click();
  await expect(page).toHaveURL(new RegExp(`/\\?agent=${code}$`));
  await expect(page.getByRole("radio", { name: PRIVATE_NAME })).toHaveAttribute(
    "aria-checked",
    "true",
  );
  // 목록의 첫 에이전트라서 골라진 것이 아니라 주소가 고른 것임을 다른 에이전트로도 본다.
  await page.goto(`/?agent=${groupCode}`);
  await expect(page.getByRole("radio", { name: GROUP_NAME })).toHaveAttribute(
    "aria-checked",
    "true",
  );
});

test("에이전트 화면에서 외부 서비스 연결하기로 가면 연결 뒤 그 에이전트를 먼저 권한다", async ({
  page,
}) => {
  await createAgent(page, "먼저 만든 비서");
  const code = await createAgent(page, PRIVATE_NAME);

  await page.goto(`/agents/${code}`);
  const section = page.getByRole("region", { name: "이 에이전트가 쓰는 연결" });
  await expect(section).toContainText(
    "아직 연결한 계정이 없어요. 서비스 계정을 연결하면 이 에이전트에 바로 붙일 수 있어요.",
  );
  await section.getByRole("link", { name: "외부 서비스 연결하기" }).click();
  await expect(page).toHaveURL(new RegExp(`/connections\\?agent=${code}$`));
  await expect(
    page.getByText("보던 에이전트에서 쓸 서비스를 골라 연결해 주세요.", {
      exact: false,
    }),
  ).toBeVisible();
  await page.getByTestId("connector-card").filter({ hasText: TITLE }).click();
  await expect(page).toHaveURL(
    new RegExp(`/connections/demo-notes\\?agent=${code}$`),
  );
  await connect(page);

  const first = chooser(page).getByTestId("agent-choice").first();
  await expect(first).toContainText(PRIVATE_NAME);
  await expect(first).toContainText("방금 보던 에이전트예요.");

  // 「나중에」 로 닫아도 붙인 에이전트 절에서 다시 열 수 있다.
  await chooser(page).getByRole("button", { name: "나중에" }).click();
  await expect(chooser(page)).toHaveCount(0);
  await page.getByRole("button", { name: "쓸 에이전트 고르기" }).click();
  await expect(
    page.getByRole("heading", { name: "이 서비스를 쓸 에이전트" }),
  ).toBeVisible();
});

for (const toolState of ["켜짐", "숨김", "조회 실패"] as const) {
  test(`셸 도구 ${toolState} 상태에서도 확인 창이 위험을 알린 뒤 붙인다`, async ({
    page,
  }) => {
    const code = await createAgent(page, PRIVATE_NAME);
    // 명령 실행 도구는 관리자만 켠다. 켜진 에이전트의 도구 목록을 정해 준다.
    await page.route(`**/api/agents/${code}/tools`, (route) =>
      route.fulfill(
        toolState === "조회 실패"
          ? { status: 500, json: {} }
          : {
              json: {
                // 켜짐은 표시된 toolset, 숨김은 서버의 실제 활성 상태를 따로 검증한다.
                shellOrFileEnabled: toolState === "숨김",
                toolsets:
                  toolState === "숨김"
                    ? []
                    : [
                        {
                          name: "terminal",
                          label: "명령 실행",
                          description: "",
                          tier: "ADMIN",
                          enabled: true,
                          editable: false,
                          requiresPrivate: false,
                        },
                      ],
                unclassifiedEnabled: [],
              },
            },
      ),
    );

    await page.goto("/connections/demo-notes");
    await connect(page);
    await declineOffer(page);
    await choice(page, PRIVATE_NAME)
      .getByRole("button", { name: "이 에이전트에서 쓰기" })
      .click();
    const dialog = page.getByRole("alertdialog", {
      name: `${TITLE} 연결을 붙일까요?`,
    });
    await expect(
      dialog.getByText(
        "이 에이전트가 이 서비스의 도구를 직접 써요. 명령 실행이나 파일 도구를 함께 쓰면 연결 정보에 접근할 수 있어요. 읽은 내용이 다른 도구를 통해 인터넷으로 나갈 수도 있어요.",
      ),
    ).toBeVisible();
    await expect(dialog.getByText(SHELL_RISK_MESSAGE)).toBeVisible();
    await clickAndWaitForResponse(
      page,
      dialog.getByRole("button", { name: "붙이기" }),
      "PUT",
      new RegExp(`^/api/agents/${code}/connections/demo-notes$`),
    );
    await expect(dialog).toBeHidden();
    await expect(
      page.getByRole("heading", { name: `「${PRIVATE_NAME}」에 붙였어요` }),
    ).toBeVisible();
  });
}

test("붙일 수 있는 에이전트가 없으면 연결된 카드와 고르기 영역이 에이전트를 만드는 길로 안내한다", async ({
  page,
}, testInfo) => {
  await connectDemoConnector(ownerOf(testInfo).email);

  await page.goto("/connections");
  const card = page.getByTestId("connector-card").filter({ hasText: TITLE });
  await expect(card.getByTestId("connector-binding-count")).toHaveText(
    "붙인 에이전트 0개",
  );
  await expect(
    card.getByText(
      "아직 쓰는 에이전트가 없어요. 눌러서 쓸 에이전트를 골라 주세요.",
      { exact: true },
    ),
  ).toBeVisible();
  await card.click();

  await expect(
    page.getByRole("heading", { name: "이 서비스를 쓸 에이전트" }),
  ).toBeVisible();
  await expect(chooser(page)).toContainText(
    "이 서비스를 붙일 수 있는 내 에이전트가 없어요.",
  );
  await chooser(page).getByRole("link", { name: "에이전트 만들기" }).click();
  await expect(page.getByRole("dialog", { name: "새 에이전트" })).toBeVisible();
  // 창을 연 뒤 주소에서 new=1 을 지워 뒤로 가기나 새로 고침에 창이 다시 열리지 않는다.
  await expect(page).toHaveURL(/\/agents$/);
});

test("처음 연결을 마치면 전문 에이전트를 제안하고, 받으면 미리 채운 값으로 만들어 그 연결을 붙인다", async ({
  page,
}) => {
  await page.goto("/connections/demo-notes");
  await connect(page);

  await expect(
    page.getByRole("heading", { name: `${TITLE} 연결이 끝났어요` }),
  ).toBeFocused();
  await expect(offer(page)).toContainText(
    "이 서비스만 맡는 전문 에이전트를 만들까요?",
  );
  await expect(chooser(page)).toHaveCount(0);
  await offer(page).getByRole("button", { name: "만들기" }).click();

  const form = page.getByTestId("specialist-agent-form");
  await expect(form.getByRole("textbox", { name: "이름" })).toHaveValue(`${TITLE} 담당`);
  await expect(form.getByRole("textbox", { name: "한 줄 소개" })).toHaveValue(
    `${TITLE} 일을 맡아요`,
  );
  await expect(form.getByRole("button", { name: "🤖" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  // 붙은 에이전트가 없으니 뗄 것도 없다.
  await expect(form.getByRole("checkbox")).toHaveCount(0);
  await clickAndWaitForResponse(
    page,
    form.getByRole("button", { name: "만들기" }),
    "POST",
    new RegExp(`^${CONNECTOR_PATH}/specialist$`),
  );

  await expect(
    page.getByRole("heading", { name: "전문 에이전트를 만들었어요" }),
  ).toBeVisible();
  await expect(offer(page)).toContainText(
    `「${TITLE} 담당」을 만들고 「${TITLE}」를 붙였어요.`,
  );
  await expect(page.getByTestId("connection-binding")).toContainText(
    `${TITLE} 담당`,
  );
  const agents = (await (await page.request.get("/api/agents")).json()) as {
    name: string;
    description: string | null;
    visibility: string;
  }[];
  expect(agents.find((agent) => agent.name === `${TITLE} 담당`)).toMatchObject(
    { description: `${TITLE} 일을 맡아요`, visibility: "PRIVATE" },
  );
});

test("에이전트 목록의 「전문 에이전트 만들기」 는 연결한 서비스를 골라 만들고 켜 둔 떼기 칸이 다른 에이전트에서 뗀다", async ({
  page,
}, testInfo) => {
  await connectDemoConnector(ownerOf(testInfo).email);
  const assistant = await createAgent(page, "비서");
  expect(
    (
      await page.request.put(`/api/agents/${assistant}/connections/demo-notes`)
    ).ok(),
  ).toBeTruthy();

  await page.goto("/agents");
  await page.getByRole("button", { name: "전문 에이전트 만들기" }).click();
  const dialog = page.getByRole("dialog", { name: "전문 에이전트 만들기" });
  await dialog
    .getByRole("listitem")
    .filter({ hasText: TITLE })
    .getByRole("button", { name: "이 서비스로 만들기" })
    .click();
  const detach = dialog.getByRole("checkbox", {
    name: "「비서」에서는 떼고 맡기기만",
  });
  await expect(detach).toBeChecked();
  await expect(detach).toBeEnabled();
  await dialog.getByRole("button", { name: "만들기" }).click();

  await expect(page).toHaveURL(/\/agents\/a[a-z0-9]{10}$/);
  const connections = (await (
    await page.request.get(`/api/agents/${assistant}/connections`)
  ).json()) as { connections: { connectorId: string; bound: boolean }[] };
  expect(
    connections.connections.find((item) => item.connectorId === "demo-notes")
      ?.bound,
  ).toBe(false);
});

test("에이전트 목록의 만들기 창은 생성 요청이 끝나기 전에는 닫히지 않고, 실패하면 다시 닫힌다", async ({
  page,
}, testInfo) => {
  await connectDemoConnector(ownerOf(testInfo).email);
  // 생성 응답을 붙잡아 두었다가 거절로 돌려준다. 요청 중 창을 닫고 다시 열어 두 번 만드는 일을 막는지 본다.
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let requests = 0;
  await page.route("**/api/connections/demo-notes/specialist", async (route) => {
    requests += 1;
    await held;
    await route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({ code: "AGENT_LIMIT_REACHED" }),
    });
  });

  await page.goto("/agents");
  await page.getByRole("button", { name: "전문 에이전트 만들기" }).click();
  const dialog = page.getByRole("dialog", { name: "전문 에이전트 만들기" });
  await dialog
    .getByRole("listitem")
    .filter({ hasText: TITLE })
    .getByRole("button", { name: "이 서비스로 만들기" })
    .click();
  await dialog.getByRole("button", { name: "만들기" }).click();
  await expect.poll(() => requests).toBe(1);

  await expect(dialog.getByRole("button", { name: "닫기" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.mouse.click(5, 5);
  await expect(dialog.getByTestId("specialist-agent-form")).toBeVisible();

  release();
  await expect(dialog.getByRole("alert")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  expect(requests).toBe(1);
});

test("붙인 뒤 서버가 반영을 마치면 새로 고치지 않아도 붙이는 중이 끝난다", async ({
  page,
}) => {
  await page.clock.install();
  const code = await createAgent(page, PRIVATE_NAME);
  // 반영 예정 시각이 지나 Control Plane 이 바인딩을 READY 로 바꾼 것을 흉내 낸다. 바꾸기 전에는 실제 응답을 그대로 둔다.
  let applied = false;
  let reads = 0;
  await page.route(`**${CONNECTOR_PATH}`, async (route) => {
    if (route.request().method() !== "GET") return route.fallback();
    reads += 1;
    const response = await route.fetch();
    if (!applied) return route.fulfill({ response });
    const connection = await response.json();
    return route.fulfill({
      response,
      json: {
        ...connection,
        bindings: connection.bindings.map((binding: object) => ({
          ...binding,
          status: "READY",
        })),
      },
    });
  });

  await page.goto("/connections/demo-notes");
  await connect(page);
  await declineOffer(page);
  await clickAndWaitForResponse(
    page,
    choice(page, PRIVATE_NAME).getByRole("button", {
      name: "이 에이전트에서 쓰기",
    }),
    "PUT",
    new RegExp(`^/api/agents/${code}/connections/demo-notes$`),
  );
  const binding = page.getByTestId("connection-binding");
  await expect(binding).toContainText("붙이는 중");
  await expect(page.getByTestId("connector-bound-result")).toContainText(
    "몇 분 안에 쓸 수 있어요.",
  );

  applied = true;
  await page.clock.runFor(BINDING_POLL_MS);
  await expect(binding).not.toContainText("붙이는 중");
  await expect(page.getByTestId("connector-bound-result")).toContainText(
    "바로 쓸 수 있어요.",
  );

  // 끝난 뒤에는 더 읽지 않는다.
  const settled = reads;
  await page.clock.runFor(BINDING_POLL_MS * 3);
  expect(reads).toBe(settled);
});
