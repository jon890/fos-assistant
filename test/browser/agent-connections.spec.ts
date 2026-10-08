import {
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";
import type { AgentToolsView } from "../../web/src/lib/agent.ts";
import type { CatalogToolset } from "../../web/src/lib/toolset-catalog.ts";
import type {
  BrowserContext,
  Page,
} from "../../web/node_modules/@playwright/test/index.js";

/**
 * 에이전트 상세의 「이 에이전트가 쓰는 연결」 에서 연결을 붙이고 떼는 흐름을 본다.
 *
 * <p>mobile 과 desktop 이 같은 Control Plane 을 함께 쓰므로 project 마다 다른 사용자로 로그인한다. 검사마다 시험 커넥터를
 * 연결하고, 끝나면 만든 에이전트를 지운 뒤 연결을 해제한다. 그룹에 공개한 에이전트가 남으면 그룹 공개 에이전트가 하나라고
 * 가정하는 다른 검사가 어긋난다.
 */

const NAME = "연결 쓰는 비서";
const TITLE = "검사용 메모";
/** 셸과 파일 도구가 켜진 에이전트에 연결을 붙일 때 붙이기 확인 창이 더 알리는 글이다. */
const SHELL_RISK_MESSAGE =
  "격리를 적용하지 않은 에이전트는 연결 도구의 승인 없이 그 서비스를 부를 수 있어요. 격리한 에이전트도 연결 도구로 읽은 내용을 인터넷으로 보낼 수 있어요.";
/** 연결이 붙은 에이전트에서 셸과 파일 도구를 켤 때 도구 확인 창이 더 알리는 글이다. */
const CONNECTION_RISK_MESSAGE =
  "격리를 적용하지 않은 에이전트는 붙은 연결의 비밀값을 이 도구로 읽고, 연결 도구의 승인 없이 그 서비스를 부를 수 있어요. 격리한 에이전트도 연결 도구로 읽은 내용을 인터넷으로 보낼 수 있어요.";

function ownerOf(projectName: string) {
  return {
    email: `connections-${projectName}@example.com`,
    name: "연결 붙이는 사용자",
  };
}

async function loginAs(
  context: BrowserContext,
  page: Page,
  user: { email: string; name: string },
) {
  await setSession(context, user);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
}

async function createAgent(page: Page, visibility?: "GROUP"): Promise<string> {
  const response = await page.request.post("/api/agents", {
    data: { name: NAME, visibility },
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { code: string }).code;
}

function section(page: Page) {
  return page.getByRole("region", { name: "이 에이전트가 쓰는 연결" });
}

test.beforeEach(async ({ context, page }, testInfo) => {
  const owner = ownerOf(testInfo.project.name);
  await loginAs(context, page, owner);
  await connectDemoConnector(owner.email);
});

test.afterEach(async ({ context, page }, testInfo) => {
  const owner = ownerOf(testInfo.project.name);
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

test("비공개 에이전트에 연결을 붙이면 위험을 알리고 반영 대기가 되며 떼면 붙지 않음이 된다", async ({
  page,
}) => {
  const code = await createAgent(page);
  await page.goto(`/agents/${code}`);

  const row = section(page).getByTestId("agent-connection");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(TITLE);
  await expect(row).toContainText("도구 4개");
  await expect(row.getByTestId("agent-connection-state")).toHaveText(
    "붙지 않음",
  );

  await row.getByRole("button", { name: "붙이기" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: `${TITLE} 연결을 붙일까요?`,
  });
  await expect(
    dialog.getByText(
      "이 에이전트가 이 연결의 도구를 직접 써요. 운영에서 격리를 적용하지 않은 에이전트는 터미널이나 파일 도구로 연결의 비밀값에 닿을 수 있어요.",
    ),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "붙이기" }).click();
  await expect(dialog).toBeHidden();
  await expect(row.getByTestId("agent-connection-state")).toHaveText(
    "반영 대기",
  );

  // 새로 읽어도 붙은 채이고, 연결 화면의 붙인 에이전트 목록에도 이 에이전트가 보인다.
  await page.reload();
  await expect(
    section(page).getByTestId("agent-connection-state"),
  ).toHaveText("반영 대기");
  await page.goto("/connections/demo-notes");
  const bound = page.getByTestId("connection-binding");
  await expect(bound).toHaveCount(1);
  await expect(bound).toContainText(NAME);
  await expect(bound).toContainText("반영 대기");

  await page.goto(`/agents/${code}`);
  await section(page).getByRole("button", { name: "떼기" }).click();
  await expect(
    section(page).getByTestId("agent-connection-state"),
  ).toHaveText("붙지 않음");
  await expect(
    section(page).getByRole("button", { name: "붙이기" }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("그룹 공개 에이전트에는 붙이기 단추 대신 까닭이 보인다", async ({
  page,
}) => {
  const code = await createAgent(page, "GROUP");
  await page.goto(`/agents/${code}`);

  await expect(section(page)).toContainText(
    "비공개 에이전트에만 붙일 수 있어요.",
  );
  await expect(section(page).getByTestId("agent-connection")).toContainText(
    TITLE,
  );
  await expect(
    section(page).getByRole("button", { name: "붙이기" }),
  ).toHaveCount(0);
});

test("붙이기가 실패해도 서버에 남은 바인딩을 다시 읽어 보인다", async ({
  page,
}) => {
  const code = await createAgent(page);
  // 서버는 바인딩을 만들었지만 응답이 실패로 온 경우를 흉내 낸다. 대시보드가 실패해 반영 대기로 남는 경우가 이렇게 보인다.
  await page.route(
    `**/api/agents/${code}/connections/demo-notes`,
    async (route) => {
      if (route.request().method() !== "PUT") {
        await route.fallback();
        return;
      }
      const response = await route.fetch();
      expect(response.ok(), "실제 붙이기는 성공해야 한다").toBeTruthy();
      await route.fulfill({
        status: 502,
        json: { code: "CONNECTOR_OPERATION_FAILED", message: "실패" },
      });
    },
  );
  await page.goto(`/agents/${code}`);

  const row = section(page).getByTestId("agent-connection");
  await row.getByRole("button", { name: "붙이기" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: `${TITLE} 연결을 붙일까요?`,
  });
  await dialog.getByRole("button", { name: "붙이기" }).click();
  await expect(dialog.getByRole("alert")).toHaveText("연결을 마치지 못했어요.");
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  await expect(row.getByTestId("agent-connection-state")).toHaveText(
    "반영 대기",
  );
  await expect(row.getByRole("button", { name: "떼기" })).toBeVisible();
});

test("셸 도구와 연결을 함께 쓰면 붙일 때와 도구를 켤 때 그 위험을 알린다", async ({
  context,
  page,
}) => {
  // 명령 실행 도구는 관리자만 켠다. 관리자가 자기 에이전트에 연결을 붙인다.
  await loginAs(context, page, { email: TEST_EMAIL, name: "브라우저 테스트" });
  const catalogResponse = await page.request.get("/api/admin/toolsets");
  expect(catalogResponse.ok()).toBeTruthy();
  const catalog = await catalogResponse.json() as CatalogToolset[];
  const originalHidden = catalog.filter((tool) => tool.hidden).map((tool) => tool.name);
  await connectDemoConnector(TEST_EMAIL);
  const code = await createAgent(page);
  try {
    await page.goto(`/agents/${code}`);
    const row = section(page).getByTestId("agent-connection");
    const bindDialog = page.getByRole("alertdialog", {
      name: `${TITLE} 연결을 붙일까요?`,
    });

    // 셸 도구가 꺼져 있으면 붙이기 확인 창이 셸 위험을 알리지 않는다.
    await row.getByRole("button", { name: "붙이기" }).click();
    await expect(bindDialog).toBeVisible();
    await expect(bindDialog.getByText(SHELL_RISK_MESSAGE)).toHaveCount(0);
    await bindDialog.getByRole("button", { name: "붙이기" }).click();
    await expect(bindDialog).toBeHidden();

    // 연결이 붙은 에이전트에서 명령 실행 도구를 켜면 도구 확인 창이 연결의 위험을 알린다.
    const terminal = page
      .getByRole("region", { name: "도구", exact: true })
      .locator("li")
      .filter({ hasText: "명령 실행" });
    await terminal.getByRole("switch").click();
    const toolDialog = page.getByRole("alertdialog", {
      name: "명령 실행 도구 켜기",
    });
    await expect(toolDialog.getByText(CONNECTION_RISK_MESSAGE)).toBeVisible();
    await toolDialog.getByRole("button", { name: "켜기" }).click();
    await expect(terminal.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "true",
    );

    // 선택 목록에서 셸과 스킬을 숨겨도 실제 활성 상태와 연결의 위험 안내는 남는다.
    const enabled = await (await page.request.get(`/api/admin/agents/${code}/tools`)).json() as AgentToolsView;
    expect((await page.request.put(`/api/admin/agents/${code}/tools`, {
      data: { enabled: [...new Set([...enabled.toolsets.filter((tool) => tool.enabled).map((tool) => tool.name), "skills"])] },
    })).ok()).toBeTruthy();
    expect((await page.request.put("/api/admin/toolsets", {
      data: { hidden: [...new Set([...originalHidden, "terminal", "skills"])] },
    })).ok()).toBeTruthy();
    await page.reload();
    await expect(terminal.getByRole("switch")).toHaveCount(0);
    const hidden = await (await page.request.get(`/api/agents/${code}/tools`)).json() as AgentToolsView;
    expect(hidden.toolsets.some((tool) => ["terminal", "skills"].includes(tool.name))).toBe(false);
    expect(hidden.shellOrFileEnabled).toBe(true);
    expect(hidden.skillsEnabled).toBe(true);

    // 셸 도구가 켜진 에이전트에 다시 붙이면 붙이기 확인 창이 셸 위험을 알린다.
    await row.getByRole("button", { name: "떼기" }).click();
    await expect(row.getByTestId("agent-connection-state")).toHaveText(
      "붙지 않음",
    );
    await row.getByRole("button", { name: "붙이기" }).click();
    await expect(bindDialog.getByText(SHELL_RISK_MESSAGE)).toBeVisible();
    await bindDialog.getByRole("button", { name: "취소" }).click();
    await expect(bindDialog).toBeHidden();
  } finally {
    expect((await page.request.put("/api/admin/toolsets", { data: { hidden: originalHidden } })).ok()).toBeTruthy();
    const deleted = await page.request.delete(`/api/agents/${code}`);
    expect(
      deleted.status(),
      `검사가 만든 에이전트 ${code} 를 지우지 못했다`,
    ).toBe(204);
    await disconnectDemoConnector(TEST_EMAIL);
  }
});
