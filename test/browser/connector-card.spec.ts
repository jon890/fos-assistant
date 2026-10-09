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
  ).toHaveCount(3);
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

test("본문에 그릴 줄이 없는 카드는 빈 본문을 그리지 않는다", async ({
  page,
}) => {
  // 도구, 링크, 아이콘이 없고 연결하지 않은 카드는 도구 요약, 붙인 에이전트 수, 링크가 모두 없다.
  const bare = connector("bare-card");
  await listConnectors(page, [bare, rich]);
  await page.goto("/connections");
  const cards = page.getByTestId("connector-card");
  await expect(cards).toHaveCount(2);
  await expect(cards.nth(0).locator("[data-slot=card-content]")).toHaveCount(0);
  // 같은 선택자가 본문이 있는 카드에서는 잡힌다.
  await expect(cards.nth(1).locator("[data-slot=card-content]")).toHaveCount(1);
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
    if (slot === "card-title") {
      await expect(element.locator("span")).toHaveCSS(
        "text-overflow",
        "ellipsis",
      );
    } else {
      expect(overflow.scroll, `${slot} scrollWidth`).toBeLessThanOrEqual(
        overflow.client,
      );
    }
  }
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("두 화면 폭에서 각 구역의 열 수와 카드 높이, 주 단추 위치를 맞추고 넘치지 않는다", async ({
  page,
}) => {
  await listConnectors(page, [
    connector("long-grid", {
      title: "긴서비스".repeat(30),
      description: "공백없는긴설명".repeat(60),
      icon: SVG_ICON,
      myStatus: "READY",
    }),
    rich,
    connector("ready-grid", { myStatus: "READY" }),
    connector("pending-grid", { myStatus: "PENDING", description: "" }),
    connector("unavailable-grid", { available: false, myStatus: "READY" }),
    connector("bare-grid", { description: "" }),
    connector("extra-grid"),
  ]);
  await page.goto("/connections");
  await expect(page.getByTestId("connector-card")).toHaveCount(7);
  const expectedColumns = page.viewportSize()!.width >= 1280 ? 3 : 1;
  const connected = page.getByRole("region", {
    name: "연결한 서비스",
    exact: true,
  });
  const available = page.getByRole("region", {
    name: "연결할 수 있는 서비스",
    exact: true,
  });
  for (const section of [connected, available]) {
    const cards = section.getByTestId("connector-card");
    expect(
      await section
        .getByTestId("connector-grid")
        .evaluate(
          (node) =>
            getComputedStyle(node).gridTemplateColumns.split(" ").length,
        ),
    ).toBe(expectedColumns);
    const layout = await cards.evaluateAll((nodes) =>
      nodes.map((node) => {
        const card = node.getBoundingClientRect();
        const positions = [
          "[data-slot=card-title]",
          "[data-slot=card-description]",
          "[data-slot=badge]",
          "[data-testid=connector-action]",
        ].map(
          (selector) =>
            node.querySelector(selector)!.getBoundingClientRect().top -
            card.top,
        );
        return {
          height: card.height,
          positions,
          left: card.left,
          top: card.top,
        };
      }),
    );
    for (const item of layout) {
      expect(item.height).toBeCloseTo(layout[0].height, 0);
      item.positions.forEach((position, index) =>
        expect(position).toBeCloseTo(layout[0].positions[index], 0),
      );
    }
    expect(new Set(layout.map((item) => item.left)).size).toBe(expectedColumns);
    expect(layout.filter((item) => item.top === layout[0].top)).toHaveLength(
      expectedColumns,
    );
  }
  await expect(
    available.getByTestId("connector-action").first(),
  ).toHaveAccessibleName(/^연결하기/);
  await expect(
    connected.getByTestId("connector-action").first(),
  ).toHaveAccessibleName(/^연결 확인/);
  await expect(
    connected.locator("[data-slot=card-description]").first(),
  ).toHaveCSS("-webkit-line-clamp", "1");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("연결한 서비스를 먼저 모으고 세 상태를 글자와 의미 색으로 구분한다", async ({
  page,
}) => {
  await listConnectors(page, [
    connector("disconnected-state"),
    connector("pending-state", { myStatus: "PENDING" }),
    connector("ready-state", { myStatus: "READY" }),
  ]);
  await page.goto("/connections");
  const connected = page.getByRole("region", {
    name: "연결한 서비스",
    exact: true,
  });
  const available = page.getByRole("region", {
    name: "연결할 수 있는 서비스",
    exact: true,
  });
  await expect(connected.getByTestId("connector-card")).toHaveCount(2);
  await expect(available.getByTestId("connector-card")).toHaveCount(1);
  expect((await connected.boundingBox())!.y).toBeLessThan(
    (await available.boundingBox())!.y,
  );
  for (const [section, label, variant] of [
    [connected, "준비 중", "warning"],
    [connected, "연결됨", "success"],
    [available, "연결 안 됨", "outline"],
  ] as const) {
    const badge = section
      .locator("[data-slot=badge]")
      .filter({ hasText: label });
    await expect(badge).toHaveText(label);
    await expect(badge).toHaveAttribute("data-variant", variant);
  }
  for (const count of await connected
    .getByTestId("connector-binding-count")
    .all()) {
    await expect(count).toHaveText("붙인 에이전트 0개");
  }
});

test("중간 폭에서는 두 열로 보인다", async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 900 });
  await listConnectors(page, [connector("first"), connector("second"), rich]);
  await page.goto("/connections");
  await expect(page.getByTestId("connector-card")).toHaveCount(3);
  expect(
    await page
      .getByTestId("connector-grid")
      .evaluate(
        (node) => getComputedStyle(node).gridTemplateColumns.split(" ").length,
      ),
  ).toBe(2);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
