import {
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
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
  await expect(row).toContainText("도구 3개");
  await expect(row.getByTestId("agent-connection-state")).toHaveText(
    "붙지 않음",
  );

  await row.getByRole("button", { name: "붙이기" }).click();
  const dialog = page.getByRole("alertdialog", {
    name: `${TITLE} 연결을 붙일까요?`,
  });
  await expect(
    dialog.getByText(
      "이 에이전트가 이 연결의 도구를 직접 써요. 터미널이나 파일 도구가 켜진 에이전트는 연결의 비밀값에 닿을 수 있어요.",
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
