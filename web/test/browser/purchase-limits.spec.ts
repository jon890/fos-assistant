import { expect, test } from "./fixtures.ts";

const SHOP_ID = "demo-shop";

/** 구매 도구 하나를 선언한 검사용 플러그인이다. 입력 칸이 없다. */
const shopConnector = {
  id: SHOP_ID,
  title: "검사용 쇼핑",
  description: "검사에서만 쓰는 쇼핑 플러그인입니다.",
  myStatus: "READY",
  available: true,
  bindings: [],
  tools: [
    {
      name: "prepare_checkout",
      title: "주문서 준비",
      risk: "READ",
      approval: "NONE",
      grant: false,
    },
    {
      name: "fill_checkout",
      title: "주문서 채우기",
      risk: "PURCHASE",
      approval: "ALWAYS",
      grant: false,
    },
  ],
  fields: [],
};

const ready = {
  connectorId: SHOP_ID,
  status: "READY",
  secretPrefixes: {},
  values: {},
  checkedAt: "2026-11-13T01:00:00Z",
  bindings: [],
  undeclaredTools: 0,
};

const closed = {
  orderKrw: 0,
  dailyKrw: 0,
  spentTodayKrw: 0,
  remainingTodayKrw: 0,
};

test.beforeEach(async ({ page }) => {
  await page.route(`**/api/connections/${SHOP_ID}`, (route) =>
    route.fulfill({ json: ready }),
  );
  await page.route("**/api/connector-grants", (route) =>
    route.fulfill({ json: [] }),
  );
});

test("구매 도구가 있는 연결 화면은 구매 상한을 보이고, 바꾸면 확인 창에서 확인해야 저장한다", async ({
  page,
}) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: [shopConnector] }),
  );
  let financialRead = false;
  await page.route("**/api/financial-limits", (route) => {
    financialRead = true;
    return route.fulfill({ status: 500, json: {} });
  });
  await page.route("**/api/purchase-limits", (route) =>
    route.fulfill({ json: closed }),
  );
  const saved: unknown[] = [];
  await page.route("**/api/purchase-limits/*", (route) => {
    saved.push({
      path: new URL(route.request().url()).pathname,
      body: route.request().postDataJSON(),
    });
    return route.fulfill({
      json: { ...closed, orderKrw: 50000 },
    });
  });
  await page.goto(`/connections/${SHOP_ID}`);

  const section = page.getByTestId("purchase-limits");
  await expect(section).toContainText("구매 상한");
  await expect(section).toContainText(
    "두 상한이 모두 0 보다 커야 에이전트가 주문서를 준비할 수 있어요.",
  );
  const row = section.getByTestId("purchase-limit-order");
  await row.getByRole("textbox").fill("1.5");
  await expect(row).toContainText("0 이상 1조 미만의 원 단위 숫자로");
  await expect(row.getByRole("button", { name: "저장" })).toBeDisabled();

  await row.getByRole("textbox").fill("50,000");
  await row.getByRole("button", { name: "저장" }).click();
  await expect(page.getByText("구매 상한을 바꿀까요?")).toBeVisible();
  expect(saved).toEqual([]);
  await page.getByRole("button", { name: "바꾸기" }).click();
  await expect(page.getByText("구매 상한을 바꿀까요?")).toHaveCount(0);
  expect(saved).toEqual([
    { path: "/api/purchase-limits/order", body: { value: 50000 } },
  ]);
  await expect(page.getByTestId("financial-limits")).toHaveCount(0);
  expect(financialRead).toBe(false);
});

test("구매 도구가 없는 연결 화면은 구매 상한을 읽지 않는다", async ({
  page,
}) => {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({
      json: [
        {
          ...shopConnector,
          tools: shopConnector.tools.filter(
            (tool) => tool.risk !== "PURCHASE",
          ),
        },
      ],
    }),
  );
  let read = false;
  await page.route("**/api/purchase-limits", (route) => {
    read = true;
    return route.fulfill({ json: closed });
  });
  await page.goto(`/connections/${SHOP_ID}`);
  await expect(page.getByText("할 수 있는 일")).toBeVisible();
  await expect(page.getByTestId("purchase-limits")).toHaveCount(0);
  expect(read).toBe(false);
});
