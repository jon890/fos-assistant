import { expect, test } from "./fixtures.ts";

const SVG_ICON =
  "data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciLz4=";

function tool(name: string, risk: string) {
  return { name, title: null, risk, approval: "REQUIRED", grant: true };
}

function connector(id: string, change: Record<string, unknown> = {}) {
  return {
    id,
    title: `검사용 ${id}`,
    description: `${id} 커넥터의 설명이에요.`,
    icon: null,
    link: null,
    myStatus: "DISCONNECTED",
    available: true,
    bindings: [],
    fields: [],
    tools: [],
    ...change,
  };
}

const rich = connector("rich-card", {
  icon: SVG_ICON,
  link: "https://example.com/rich",
  tools: [
    tool("read_one", "READ"),
    tool("write_one", "WRITE"),
    tool("purge_one", "DESTRUCTIVE"),
  ],
});

async function listConnectors(
  page: import("@playwright/test").Page,
  items: unknown[],
) {
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: items }),
  );
}

test("아이콘과 링크가 있는 카드가 도구 요약과 함께 보인다", async ({
  page,
}) => {
  await listConnectors(page, [rich]);
  await page.goto("/connections");
  const card = page.getByTestId("connector-card");
  await expect(card.getByTestId("connector-icon")).toHaveAttribute(
    "src",
    SVG_ICON,
  );
  const link = card.getByTestId("connector-link");
  await expect(link).toHaveAttribute("href", "https://example.com/rich");
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noopener noreferrer");
  await expect(
    card.getByRole("link", { name: /검사용 rich-card/ }),
  ).toHaveCount(2);
  await expect(link).toHaveAccessibleName(/검사용 rich-card/);
  await expect(card.getByTestId("connector-tool-summary")).toHaveText(
    "도구 3개 · 조회 1 · 쓰기 1 · 되돌리기 어려운 쓰기 1",
  );
});

test("아이콘과 링크 칸이 없거나 모양이 틀리면 기본 아이콘과 링크 없음으로 그린다", async ({
  page,
}) => {
  const missing = connector("no-fields");
  delete (missing as Record<string, unknown>).icon;
  delete (missing as Record<string, unknown>).link;
  const bad = connector("bad-fields", {
    icon: "data:text/html;base64,PHNjcmlwdD4=",
    link: "javascript:alert(1)",
  });
  await listConnectors(page, [missing, bad]);
  await page.goto("/connections");
  const cards = page.getByTestId("connector-card");
  await expect(cards).toHaveCount(2);
  for (let i = 0; i < 2; i += 1) {
    await expect(
      cards.nth(i).getByTestId("connector-icon-default"),
    ).toBeVisible();
    await expect(cards.nth(i).getByTestId("connector-icon")).toHaveCount(0);
    await expect(cards.nth(i).getByTestId("connector-link")).toHaveCount(0);
  }
});

test("카드의 설명 글을 눌러도 상세 화면으로 간다", async ({ page }) => {
  await listConnectors(page, [rich]);
  await page.route("**/api/connections/rich-card", (route) =>
    route.fulfill({
      json: {
        connectorId: "rich-card",
        status: "DISCONNECTED",
        secretPrefixes: {},
        values: {},
        checkedAt: null,
        bindings: [],
        undeclaredTools: 0,
      },
    }),
  );
  await page.goto("/connections");
  // 설명은 이름 링크의 덧씌움 아래에 있어 locator.click() 은 포인터 가로채기로 실패한다.
  const box = await page
    .getByText("rich-card 커넥터의 설명이에요.")
    .boundingBox();
  if (!box) throw new Error("설명 요소의 상자를 찾지 못했어요");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await expect(page).toHaveURL(/\/connections\/rich-card$/);
});

test("외부 링크는 새 탭으로 열고 현재 주소는 그대로다", async ({ page }) => {
  await listConnectors(page, [rich]);
  // 새 탭의 요청은 page.route 에 걸리지 않아 context 에서 막아 밖으로 나가지 않게 한다.
  await page
    .context()
    .route("https://example.com/**", (route) => route.fulfill({ body: "ok" }));
  await page.goto("/connections");
  const before = page.url();
  const popup = page.waitForEvent("popup");
  await page.getByTestId("connector-link").click();
  const opened = await popup;
  await opened.waitForLoadState();
  expect(opened.url()).toBe("https://example.com/rich");
  expect(page.url()).toBe(before);
  await opened.close();
});

test("상세 화면 머리에 아이콘과 링크가 보인다", async ({ page }) => {
  await listConnectors(page, [rich]);
  await page.route("**/api/connections/rich-card", (route) =>
    route.fulfill({
      json: {
        connectorId: "rich-card",
        status: "DISCONNECTED",
        secretPrefixes: {},
        values: {},
        checkedAt: null,
        bindings: [],
        undeclaredTools: 0,
      },
    }),
  );
  await page.goto("/connections/rich-card");
  await expect(page.getByTestId("connector-icon")).toHaveAttribute(
    "src",
    SVG_ICON,
  );
  await expect(page.getByTestId("connector-link")).toHaveAttribute(
    "href",
    "https://example.com/rich",
  );
});

test("폭 360 에서 공백 없는 긴 이름과 설명이 카드 밖으로 넘치지 않는다", async ({
  page,
}) => {
  const title = "가".repeat(80);
  const description = "나".repeat(80);
  await page.setViewportSize({ width: 360, height: 800 });
  await listConnectors(page, [
    connector("long-card", {
      title,
      description,
      icon: SVG_ICON,
      link: "https://example.com/long",
      tools: [tool("read_one", "READ")],
    }),
  ]);
  await page.goto("/connections");
  const card = page.getByTestId("connector-card");
  await expect(card).toContainText(title);
  const cardBox = await card.boundingBox();
  if (!cardBox) throw new Error("카드의 상자를 찾지 못했어요");
  // 이름은 인라인 링크가 아니라 블록 요소(card-title)로 잡아야 clientWidth 가 0 이 아니다.
  for (const slot of ["card-title", "card-description"]) {
    const element = card.locator(`[data-slot=${slot}]`);
    const box = await element.boundingBox();
    if (!box) throw new Error(`${slot} 의 상자를 찾지 못했어요`);
    expect(box.x + box.width, `${slot} 오른쪽 끝`).toBeLessThanOrEqual(
      cardBox.x + cardBox.width,
    );
    const overflow = await element.evaluate((node) => ({
      scroll: node.scrollWidth,
      client: node.clientWidth,
    }));
    expect(overflow.client, `${slot} clientWidth`).toBeGreaterThan(0);
    expect(overflow.scroll, `${slot} scrollWidth`).toBeLessThanOrEqual(
      overflow.client,
    );
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
