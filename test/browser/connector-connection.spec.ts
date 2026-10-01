import { expect, test } from "./fixtures.ts";

const DEMO_ID = "demo-notes";
const SCOPE_ID = "scope-a";

const demoConnector = {
  id: DEMO_ID,
  title: "검사용 메모",
  description: "검사에서만 쓰는 커넥터입니다.",
  myStatus: "DISCONNECTED",
  available: true,
  fields: [
    {
      key: "token",
      label: "토큰",
      description: "검사용 토큰입니다.",
      secret: true,
      required: true,
      pattern: "^demo_[a-z]+_[0-9]{10}$",
      hasOptions: false,
      autoSelectSingle: false,
    },
    {
      key: "scope",
      label: "범위",
      description: null,
      secret: false,
      required: false,
      pattern: null,
      hasOptions: true,
      autoSelectSingle: true,
    },
  ],
};

const disconnected = {
  connectorId: DEMO_ID,
  status: "DISCONNECTED",
  secretPrefixes: {},
  values: {},
  checkedAt: null,
  agentCode: null,
  restartRequired: false,
};
const pending = {
  ...disconnected,
  status: "PENDING",
  secretPrefixes: { token: "demo_ok_" },
  values: { scope: SCOPE_ID },
  agentCode: "demo-notes-browser",
};
const ready = {
  ...pending,
  status: "READY",
  checkedAt: "2026-09-30T12:00:00Z",
};

const notFound = { code: "CONNECTOR_NOT_FOUND", message: "raw upstream" };

test.beforeEach(async ({ page }) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: [demoConnector] }),
  );
  await page.route(`**/api/connections/${DEMO_ID}/options/scope`, (route) =>
    route.fulfill({ json: [{ value: SCOPE_ID, label: "범위 A" }] }),
  );
});

test("카드에서 연결 화면으로 들어가 값을 등록하고 확인한 뒤 연결됨을 본다", async ({
  page,
}) => {
  let submitted: unknown;
  await page.route(`**/api/connections/${DEMO_ID}`, async (route) => {
    const method = route.request().method();
    if (method === "POST") {
      submitted = route.request().postDataJSON();
      return route.fulfill({ json: pending });
    }
    return route.fulfill({ json: disconnected });
  });
  await page.route(`**/api/connections/${DEMO_ID}/check`, (route) =>
    route.fulfill({ json: ready }),
  );

  await page.goto("/connections");
  const card = page.getByTestId("connector-card");
  await expect(card).toContainText("검사용 메모");
  await expect(card).toContainText("연결 안 됨");
  await card.click();

  await expect(page).toHaveURL(new RegExp(`/connections/${DEMO_ID}$`));
  await page.getByLabel("토큰").fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByLabel("범위")).toHaveValue(SCOPE_ID);
  await expect(page.getByText("하나뿐이라 자동으로 골랐어요.")).toBeVisible();
  await page.getByRole("button", { name: "연결하기" }).click();

  await expect(page.getByTestId("connection-status")).toHaveText("준비 중");
  expect(submitted).toEqual({
    values: { token: "demo_ok_0123456789", scope: SCOPE_ID },
  });
  await expect(page.getByLabel("토큰")).toHaveValue("");
  await page.getByRole("button", { name: "연결 다시 확인" }).click();
  await expect(page.getByTestId("connection-status")).toHaveText("연결됨");
  await expect(
    page.getByRole("link", { name: "에이전트 열기" }),
  ).toHaveAttribute("href", "/agents/demo-notes-browser");
  await expect(page.getByText(/마지막 확인:/)).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("등록이 거절되면 비밀 칸을 비우고 정해 둔 문구만 보인다", async ({
  page,
}) => {
  await page.route(`**/api/connections/${DEMO_ID}`, async (route) => {
    if (route.request().method() !== "POST")
      return route.fulfill({ json: disconnected });
    return route.fulfill({
      status: 400,
      json: {
        code: "CONNECTOR_CREDENTIAL_REJECTED",
        message: "raw upstream secret",
      },
    });
  });
  await page.goto(`/connections/${DEMO_ID}`);
  await page.getByLabel("토큰").fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByLabel("범위")).toHaveValue(SCOPE_ID);
  await page.getByRole("button", { name: "연결하기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "입력한 값을 확인하지 못했어요.",
  );
  await expect(page.getByText("raw upstream secret")).toHaveCount(0);
  await expect(page.getByLabel("토큰")).toHaveValue("");
});

test("선택지 조회가 실패하면 비밀 칸을 비운다", async ({ page }) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.route(`**/api/connections/${DEMO_ID}/options/scope`, (route) =>
    route.fulfill({
      status: 403,
      json: { code: "CONNECTOR_FORBIDDEN", message: "raw" },
    }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  const token = page.getByLabel("토큰");
  await token.fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "이 값으로는 쓸 수 없어요.",
  );
  await expect(token).toHaveValue("");
});

test("비밀 칸이 비어 있으면 선택지를 불러오지 못하고 입력 형식 오류를 문구로 보인다", async ({
  page,
}) => {
  await page.route(`**/api/connections/${DEMO_ID}`, async (route) => {
    if (route.request().method() !== "POST")
      return route.fulfill({ json: disconnected });
    return route.fulfill({
      status: 400,
      json: { code: "VALIDATION_FAILED", message: "raw" },
    });
  });
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByRole("button", { name: "불러오기" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "연결하기" })).toBeDisabled();
  await page.getByLabel("토큰").fill("not-a-demo-token");
  await page.getByRole("button", { name: "연결하기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "입력 형식을 확인해 주세요.",
  );
});

test("모르는 id 는 찾을 수 없는 화면을 보인다", async ({ page }) => {
  await page.route("**/api/connections/missing-id", (route) =>
    route.fulfill({ status: 404, json: notFound }),
  );
  await page.goto("/connections/missing-id");
  await expect(page.getByTestId("connector-not-found")).toContainText(
    "찾을 수 없는 서비스예요",
  );
  await expect(page.getByText("raw upstream")).toHaveCount(0);
});

test("운영 목록에서 빠진 연결은 쓸 수 없다고 알리고 해제만 한다", async ({
  page,
}) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({
      json: [
        {
          ...demoConnector,
          description: "",
          fields: [],
          available: false,
          myStatus: "READY",
        },
      ],
    }),
  );
  await page.route(`**/api/connections/${DEMO_ID}`, async (route) => {
    if (route.request().method() === "DELETE") {
      return route.fulfill({
        json: { ...disconnected, restartRequired: true },
      });
    }
    return route.fulfill({ json: ready });
  });
  await page.goto("/connections");
  await expect(page.getByTestId("connector-card")).toContainText(
    "지금은 쓸 수 없어요",
  );
  await page.getByTestId("connector-card").click();
  await expect(page.getByRole("main").getByRole("status")).toContainText(
    "지금은 쓸 수 없어요",
  );
  await expect(page.getByLabel("토큰")).toHaveCount(0);
  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByTestId("connection-status")).toHaveText("연결 안 됨");
});

test("서비스가 없으면 빈 상태를 보인다", async ({ page }) => {
  await page.route("**/api/connectors", (route) => route.fulfill({ json: [] }));
  await page.goto("/connections");
  await expect(page.getByText("연결할 수 있는 서비스가 없어요")).toBeVisible();
});

test("옛 가계부 연결 주소는 새 연결 화면으로 넘어간다", async ({ page }) => {
  await page.route("**/api/connections/fos-accountbook", (route) =>
    route.fulfill({ status: 404, json: notFound }),
  );
  await page.goto("/connections/accountbook");
  await expect(page).toHaveURL(/\/connections\/fos-accountbook$/);
});

test("관리자는 반영 대기 연결을 완료로 확인한다", async ({ page }) => {
  let confirmedPath = "";
  await page.route("**/api/admin/connections", (route) =>
    route.fulfill({
      json: [
        {
          connectorId: DEMO_ID,
          userId: 77,
          displayName: "연결 확인 사용자",
          status: "PENDING",
          agentCode: "demo-notes-browser",
          restartRequired: true,
        },
      ],
    }),
  );
  await page.route("**/api/admin/connections/*/*/confirm", (route) => {
    confirmedPath = new URL(route.request().url()).pathname;
    return route.fulfill({
      json: {
        connectorId: DEMO_ID,
        userId: 77,
        displayName: null,
        status: "READY",
        agentCode: "demo-notes-browser",
        restartRequired: false,
      },
    });
  });
  await page.goto("/connections");
  const panel = page.getByTestId("connector-admin-panel");
  await expect(panel).toContainText("연결 확인 사용자");
  await expect(panel).toContainText("검사용 메모");
  await expect(panel).toContainText("공유 gateway 를 재시작한 뒤 눌러 주세요.");
  await panel.getByRole("button", { name: "반영 완료 확인" }).click();
  await expect(
    panel.getByText("반영 확인이 필요한 연결이 없어요."),
  ).toBeVisible();
  expect(confirmedPath).toBe(`/api/admin/connections/${DEMO_ID}/77/confirm`);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
