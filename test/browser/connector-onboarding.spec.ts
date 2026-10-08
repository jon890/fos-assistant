import {
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { clickAndWaitForResponse, isolatedUser } from "./helpers.ts";
import { DEMO_TOKEN_OK } from "../e2e/fake-hermes.ts";
import type {
  Page,
  TestInfo,
} from "../../web/node_modules/@playwright/test/index.js";

/**
 * 외부 서비스를 연결한 직후 같은 자리에서 쓸 에이전트를 고르고, 붙이고, 그 에이전트와 대화로 잇는 흐름을 본다.
 *
 * <p>검사마다 따로 만든 사용자로 시험 커넥터를 연결하고, 끝나면 만든 에이전트를 지운 뒤 연결을 해제한다.
 */

const TITLE = "검사용 메모";
const CONNECTOR_PATH = "/api/connections/demo-notes";
const PRIVATE_NAME = "연결 쓰는 비서";
const GROUP_NAME = "모두의 비서";
const GROUP_REASON =
  "그룹에 공개한 에이전트라 붙일 수 없어요. 나만 쓰는 에이전트로 바꾸면 붙일 수 있어요.";
const SHELL_RISK_MESSAGE =
  "격리를 적용하지 않은 에이전트는 연결 도구의 승인 없이 그 서비스를 부를 수 있어요. 격리한 에이전트도 연결 도구로 읽은 내용을 인터넷으로 보낼 수 있어요.";

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
  await createAgent(page, GROUP_NAME, "GROUP");

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

test("셸이나 파일 도구가 켜진 에이전트를 고르면 확인 창이 그 위험을 알린 뒤 붙인다", async ({
  page,
}) => {
  const code = await createAgent(page, PRIVATE_NAME);
  // 명령 실행 도구는 관리자만 켠다. 켜진 에이전트의 도구 목록을 정해 준다.
  await page.route(`**/api/agents/${code}/tools`, (route) =>
    route.fulfill({
      json: {
        toolsets: [
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
    }),
  );

  await page.goto("/connections/demo-notes");
  await connect(page);
  await choice(page, PRIVATE_NAME)
    .getByRole("button", { name: "이 에이전트에서 쓰기" })
    .click();
  const dialog = page.getByRole("alertdialog", {
    name: `${TITLE} 연결을 붙일까요?`,
  });
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

test("붙일 수 있는 에이전트가 없으면 연결된 카드와 고르기 영역이 에이전트를 만드는 길로 안내한다", async ({
  page,
}, testInfo) => {
  await connectDemoConnector(ownerOf(testInfo).email);

  await page.goto("/connections");
  const card = page.getByTestId("connector-card").filter({ hasText: TITLE });
  await expect(card.getByTestId("connector-binding-count")).toHaveText(
    "아직 쓰는 에이전트가 없어요. 눌러서 쓸 에이전트를 골라 주세요.",
  );
  await card.click();

  await expect(
    page.getByRole("heading", { name: "이 서비스를 쓸 에이전트" }),
  ).toBeVisible();
  await expect(chooser(page)).toContainText(
    "이 서비스를 붙일 수 있는 내 에이전트가 없어요.",
  );
  await chooser(page).getByRole("link", { name: "에이전트 만들기" }).click();
  await expect(page).toHaveURL(/\/agents\?new=1$/);
  await expect(page.getByRole("dialog", { name: "새 에이전트" })).toBeVisible();
});
