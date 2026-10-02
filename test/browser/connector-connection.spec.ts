import { expect, test } from "./fixtures.ts";

const DEMO_ID = "demo-notes";
const SCOPE_ID = "scope-a";

const demoConnector = {
  id: DEMO_ID,
  title: "검사용 메모",
  description: "검사에서만 쓰는 커넥터입니다.",
  myStatus: "DISCONNECTED",
  available: true,
  tools: [
    { name: "list_scopes", title: null, risk: "READ", approval: "NONE" },
    {
      name: "write_note",
      title: "메모 쓰기",
      risk: "WRITE",
      approval: "REQUIRED",
    },
    {
      name: "purge_notes",
      title: null,
      risk: "DESTRUCTIVE",
      approval: "ALWAYS",
    },
  ],
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
  undeclaredTools: 0,
};
const pending = {
  ...disconnected,
  status: "PENDING",
  secretPrefixes: { token: "demo" },
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
  await page.getByLabel("토큰", { exact: true }).fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByLabel("범위", { exact: true })).toHaveValue(SCOPE_ID);
  await expect(page.getByText("하나뿐이라 자동으로 골랐어요.")).toBeVisible();
  await page.getByRole("button", { name: "연결하기" }).click();

  await expect(page.getByTestId("connection-status")).toHaveText("준비 중");
  await expect(page.getByText("토큰: demo", { exact: true })).toBeVisible();
  expect(submitted).toEqual({
    values: { token: "demo_ok_0123456789", scope: SCOPE_ID },
  });
  await expect(page.getByLabel("토큰", { exact: true })).toHaveValue("");
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
  await page.getByLabel("토큰", { exact: true }).fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByLabel("범위", { exact: true })).toHaveValue(SCOPE_ID);
  await page.getByRole("button", { name: "연결하기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "입력한 값을 확인하지 못했어요.",
  );
  await expect(page.getByText("raw upstream secret")).toHaveCount(0);
  await expect(page.getByLabel("토큰", { exact: true })).toHaveValue("");
});

test("앞부분이 없는 필수 비밀 칸은 연결된 상태에서만 입력됨으로 보인다", async ({
  page,
}) => {
  let current: unknown = { ...ready, secretPrefixes: {} };
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: current }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connection-status")).toHaveText("연결됨");
  await expect(page.getByText("토큰: 입력됨", { exact: true })).toBeVisible();

  current = disconnected;
  await page.reload();
  await expect(page.getByTestId("connection-status")).toHaveText("연결 안 됨");
  await expect(page.getByText("입력됨")).toHaveCount(0);
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
  const token = page.getByLabel("토큰", { exact: true });
  await token.fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "이 값으로는 쓸 수 없어요.",
  );
  await expect(token).toHaveValue("");
});

test("선택지 조회가 호출 제한에 걸리면 정해 둔 문구만 보인다", async ({
  page,
}) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.route(`**/api/connections/${DEMO_ID}/options/scope`, (route) =>
    route.fulfill({
      status: 429,
      json: { code: "CONNECTOR_RATE_LIMITED", message: "raw upstream" },
    }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await page.getByLabel("토큰", { exact: true }).fill("demo_ok_0123456789");
  await page.getByRole("button", { name: "불러오기" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "요청이 많아요. 잠시 뒤 다시 해 주세요.",
  );
  await expect(page.getByText("raw upstream")).toHaveCount(0);
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
  await page.getByLabel("토큰", { exact: true }).fill("not-a-demo-token");
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

test("형식이 틀린 id 는 서버를 부르지 않고 찾을 수 없는 화면을 보인다", async ({
  page,
}) => {
  const called: string[] = [];
  await page.route("**/api/connections/**", (route) => {
    called.push(route.request().url());
    return route.fulfill({ status: 404, json: notFound });
  });
  await page.goto("/connections/Bad_Id");
  await expect(page.getByTestId("connector-not-found")).toContainText(
    "찾을 수 없는 서비스예요",
  );
  await expect(
    page.getByRole("button", { name: "상태 다시 읽기" }),
  ).toHaveCount(0);
  expect(called).toEqual([]);
});

test("카탈로그를 읽지 못하면 쓸 수 없다고 하지 않고 다시 읽게 한다", async ({
  page,
}) => {
  let catalogOk = false;
  await page.route("**/api/connectors", (route) =>
    catalogOk
      ? route.fulfill({ json: [demoConnector] })
      : route.fulfill({
          status: 503,
          json: { code: "CONNECTOR_UNAVAILABLE", message: "raw" },
        }),
  );
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "연결 상태를 읽지 못했어요",
  );
  await expect(page.getByText("지금은 쓸 수 없어요")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "연결 해제" })).toHaveCount(0);
  catalogOk = true;
  await page.getByRole("button", { name: "상태 다시 읽기" }).click();
  await expect(page.getByLabel("토큰", { exact: true })).toBeVisible();
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
  await expect(page.getByLabel("토큰", { exact: true })).toHaveCount(0);
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
          undeclaredTools: 0,
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
        undeclaredTools: 0,
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

test("연결 화면이 도구마다 위험도와 실행 방식을 보인다", async ({ page }) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-tool")).toHaveCount(3);
  const write = page
    .getByTestId("connector-tool")
    .filter({ hasText: "메모 쓰기" });
  await expect(write).toContainText("쓰기");
  await expect(write).toContainText("실행 전에 물어봐요");
  await expect(
    page.getByTestId("connector-tool").filter({ hasText: "list_scopes" }),
  ).toContainText("바로 실행해요");
  await expect(
    page.getByTestId("connector-tool").filter({ hasText: "purge_notes" }),
  ).toContainText("아직 쓸 수 없어요");
  await expect(page.getByTestId("connection-undeclared")).toHaveCount(0);
});

test("쓰기 도구라도 늘 승인을 받게 선언했으면 아직 쓸 수 없다고 보인다", async ({
  page,
}) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({
      json: [
        {
          ...demoConnector,
          tools: [
            {
              name: "share_note",
              title: "메모 공유",
              risk: "WRITE",
              approval: "ALWAYS",
            },
          ],
        },
      ],
    }),
  );
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  const tool = page.getByTestId("connector-tool");
  await expect(tool).toContainText("메모 공유");
  await expect(tool).toContainText("아직 쓸 수 없어요");
  await expect(tool).not.toContainText("실행 전에 물어봐요");
});

test("승인한 동작을 실행하는 중이면 해제가 거절되고 정해 둔 문구를 보인다", async ({
  page,
}) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.request().method() === "DELETE"
      ? route.fulfill({
          status: 409,
          json: { code: "CONNECTOR_ACTION_EXECUTING", message: "raw upstream" },
        })
      : route.fulfill({ json: ready }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByRole("main").getByRole("alert")).toHaveText(
    "승인한 동작을 실행하는 중이에요. 끝난 뒤 다시 시도해 주세요.",
  );
  await expect(page.getByText("raw upstream")).toHaveCount(0);
  await expect(page.getByTestId("connection-status")).toHaveText("연결됨");
});

test("허락한 동작이 있으면 제목으로 보이고 다시 묻기를 누르면 거두고 줄이 사라진다", async ({
  page,
}) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: ready }),
  );
  await page.route("**/api/connector-grants", (route) =>
    route.fulfill({
      json: [
        {
          grantId: 31,
          connectorId: DEMO_ID,
          toolName: "write_note",
          title: "메모 쓰기",
          expiresAt: "2026-10-30T12:00:00Z",
        },
        {
          grantId: 32,
          connectorId: "other-connector",
          toolName: "other_tool",
          title: "다른 연결의 동작",
          expiresAt: "2026-10-30T12:00:00Z",
        },
      ],
    }),
  );
  let revokedPath = "";
  await page.route("**/api/connector-grants/*", (route) => {
    revokedPath = `${route.request().method()} ${new URL(route.request().url()).pathname}`;
    return route.fulfill({ status: 204 });
  });
  await page.goto(`/connections/${DEMO_ID}`);

  const grants = page.getByTestId("connector-grants");
  await expect(grants).toContainText("묻지 않고 실행하는 동작");
  const row = grants.getByTestId("connector-grant");
  await expect(row).toHaveCount(1);
  await expect(row).toContainText("메모 쓰기");
  await expect(row).toContainText("까지");
  await expect(row).not.toContainText("write_note");
  await expect(grants).not.toContainText("다른 연결의 동작");

  await row.getByTestId("grant-revoke").click();
  await expect(page.getByTestId("connector-grants")).toHaveCount(0);
  expect(revokedPath).toBe("DELETE /api/connector-grants/31");
});

test("연결을 해제하면 허락 목록을 다시 읽어 거둔 허락이 사라진다", async ({
  page,
}) => {
  let disconnectedNow = false;
  await page.route(`**/api/connections/${DEMO_ID}`, (route) => {
    if (route.request().method() === "DELETE") {
      disconnectedNow = true;
      return route.fulfill({ json: disconnected });
    }
    return route.fulfill({ json: disconnectedNow ? disconnected : ready });
  });
  await page.route("**/api/connector-grants", (route) =>
    route.fulfill({
      json: disconnectedNow
        ? []
        : [
            {
              grantId: 31,
              connectorId: DEMO_ID,
              toolName: "write_note",
              title: "메모 쓰기",
              expiresAt: "2026-10-30T12:00:00Z",
            },
          ],
    }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-grant")).toHaveCount(1);

  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByTestId("connection-status")).toHaveText("연결 안 됨");
  await expect(page.getByTestId("connector-grants")).toHaveCount(0);
});

test("연결 해제 뒤 허락을 다시 읽지 못하면 옛 허락을 지우고 읽지 못했다고 알린다", async ({
  page,
}) => {
  let disconnectedNow = false;
  let grantsReply: "ok" | "fail" | "empty" = "ok";
  const revokeRequests: string[] = [];
  await page.route(`**/api/connections/${DEMO_ID}`, (route) => {
    if (route.request().method() === "DELETE") {
      disconnectedNow = true;
      grantsReply = "fail";
      return route.fulfill({ json: disconnected });
    }
    return route.fulfill({ json: disconnectedNow ? disconnected : ready });
  });
  await page.route("**/api/connector-grants", (route) => {
    if (grantsReply === "fail") {
      return route.fulfill({
        status: 500,
        json: { code: "INTERNAL_ERROR" },
      });
    }
    return route.fulfill({
      json:
        grantsReply === "empty"
          ? []
          : [
              {
                grantId: 31,
                connectorId: DEMO_ID,
                toolName: "write_note",
                title: "메모 쓰기",
                expiresAt: "2026-10-30T12:00:00Z",
              },
            ],
    });
  });
  await page.route("**/api/connector-grants/*", (route) => {
    revokeRequests.push(`${route.request().method()} ${route.request().url()}`);
    return route.fulfill({ status: 204 });
  });
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-grant")).toHaveCount(1);

  await page.getByRole("button", { name: "연결 해제" }).click();
  await expect(page.getByTestId("connection-status")).toHaveText("연결 안 됨");
  await expect(page.getByTestId("connector-grant")).toHaveCount(0);
  await expect(page.getByTestId("connector-grants-unavailable")).toHaveText(
    "허락 상태를 확인하지 못했어요.",
  );

  grantsReply = "empty";
  await page.getByTestId("connector-grants-retry").click();
  await expect(page.getByTestId("connector-grants")).toHaveCount(0);
  expect(revokeRequests).toEqual([]);
});

test("허락을 처음 읽지 못해도 읽지 못했다고 알린다", async ({ page }) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: ready }),
  );
  await page.route("**/api/connector-grants", (route) =>
    route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR" } }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-grants-unavailable")).toBeVisible();
  await expect(page.getByTestId("connector-grant")).toHaveCount(0);
});

test("허락한 동작이 없으면 그 제목을 보이지 않는다", async ({ page }) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: ready }),
  );
  let read = false;
  await page.route("**/api/connector-grants", (route) => {
    read = true;
    return route.fulfill({ json: [] });
  });
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-tool")).toHaveCount(3);
  await expect.poll(() => read).toBe(true);
  await expect(page.getByText("묻지 않고 실행하는 동작")).toHaveCount(0);
});

test("도구를 선언하지 않은 커넥터는 안내 한 줄을 보인다", async ({ page }) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: [{ ...demoConnector, tools: [] }] }),
  );
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-tools-empty")).toHaveText(
    "이 연결은 조회를 뺀 모든 동작을 실행 전에 물어봐요.",
  );
  await expect(page.getByTestId("connector-tool")).toHaveCount(0);
});

test("web API 가 도구를 주지 않는 옛 응답도 안내 한 줄로 보인다", async ({
  page,
}) => {
  const { tools: _omitted, ...legacy } = demoConnector;
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: [legacy] }),
  );
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: disconnected }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-tools-empty")).toBeVisible();
});

test("선언하지 않은 도구가 있으면 본인에게 개수를 알린다", async ({ page }) => {
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: { ...ready, undeclaredTools: 2 } }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connection-undeclared")).toContainText("2개");
});

test("관리자 목록은 선언하지 않은 도구가 있는 연결을 단추 없이 보인다", async ({
  page,
}) => {
  await page.route("**/api/admin/connections", (route) =>
    route.fulfill({
      json: [
        {
          connectorId: DEMO_ID,
          userId: 78,
          displayName: "도구 확인 사용자",
          status: "READY",
          agentCode: "demo-notes-browser",
          restartRequired: false,
          undeclaredTools: 1,
        },
      ],
    }),
  );
  await page.goto("/connections");
  const panel = page.getByTestId("connector-admin-panel");
  await expect(panel).toContainText("도구 확인 사용자");
  await expect(panel).toContainText("연결됨");
  await expect(panel.getByTestId("admin-undeclared")).toContainText(
    "선언하지 않은 도구 1개",
  );
  await expect(
    panel.getByRole("button", { name: "반영 완료 확인" }),
  ).toHaveCount(0);
});

test("390px 폭에서도 도구 목록이 가로로 넘치지 않는다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await page.route(`**/api/connections/${DEMO_ID}`, (route) =>
    route.fulfill({ json: { ...ready, undeclaredTools: 2 } }),
  );
  await page.goto(`/connections/${DEMO_ID}`);
  await expect(page.getByTestId("connector-tool")).toHaveCount(3);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
