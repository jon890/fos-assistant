import { expect, hermesBaseUrl, test } from "./fixtures.ts";
import { pickAgent } from "./helpers.ts";
import { FRAME, fakeScreen, type Input } from "./fake-screen.ts";

const AGENT_TAB = "ABCDEF0123456789";
const HUMAN_TAB = "FEDCBA9876543210";

/** 브라우저를 쓰는 플러그인 하나만 있는 카탈로그다. 단추는 이 도구 이름으로 판정한다. */
const CATALOG = [
  {
    id: "naver-shopping",
    title: "네이버 쇼핑",
    description: "",
    fields: [],
    tools: [
      {
        name: "search_products",
        title: null,
        risk: "READ",
        approval: "NONE",
        grant: false,
      },
    ],
    myStatus: "READY",
    available: true,
    bindings: [],
    ownerBrowserLoginUrl: null,
    standardPlugin: false,
    singleBinding: false,
    ownerBrowser: true,
  },
];

/** 도구 시작 사건만 보내고 실행 끝을 멈춘다. 풀 때까지 그 도구가 도는 중이다. */
async function pausedBrowserTool(input: string) {
  const response = await fetch((await hermesBaseUrl()) + "/__test/script", {
    method: "POST",
    body: JSON.stringify({
      input,
      output: "에이전트 화면 검사 답",
      events: [
        {
          event: "tool.started",
          tool: "mcp__naver_shopping__search_products",
          preview: "started",
        },
      ],
      pause: true,
    }),
  });
  expect(response.ok).toBeTruthy();
}

function ofType(inputs: Input[], type: string): Input[] {
  return inputs.filter((input) => input.type === type);
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("브라우저 도구가 도는 동안 「에이전트 화면 보기」 가 보이고, 누르면 패널이 에이전트 탭을 띠와 함께 보기 전용으로 연다. 끝나면 단추만 사라진다", async ({
  page,
  hermes,
}) => {
  const fake = await fakeScreen(page);
  await page.route("**/api/connectors", (route) =>
    route.fulfill({ json: CATALOG }),
  );
  const input = `에이전트 화면 검사 ${Date.now()}`;
  await pausedBrowserTool(input);
  let released = false;
  try {
    await page.goto("/new");
    await pickAgent(page, "브라우저 비서");
    // 도구가 돌기 전에는 단추가 없다.
    await expect(page.getByTestId("agent-browser-open")).toHaveCount(0);
    await page.getByRole("textbox", { name: "메시지" }).fill(input);
    await page.getByRole("button", { name: "보내기" }).click();

    const open = page.getByTestId("agent-browser-open");
    await expect(open).toHaveText("에이전트 화면 보기");
    // 저절로 열지 않는다.
    await expect(page.getByTestId("agent-browser-panel")).toHaveCount(0);
    await open.click();
    const panel = page.getByTestId("agent-browser-panel");
    await expect(panel).toBeVisible();

    await fake.push([
      ["frame", { data: FRAME, width: 400, height: 600 }],
      [
        "tabs",
        [
          {
            id: HUMAN_TAB,
            title: "사람 탭",
            url: "https://example.com/",
            active: true,
            agent: false,
          },
          {
            id: AGENT_TAB,
            title: "에이전트 탭",
            url: "https://shopping.example.com/",
            active: false,
            agent: true,
          },
        ],
      ],
    ]);
    // 첫 탭 목록에서 에이전트가 쥔 탭으로 한 번 옮긴다.
    await expect
      .poll(() => ofType(fake.inputs, "tab").map((sent) => sent.id))
      .toEqual([AGENT_TAB]);
    await fake.push([
      [
        "tabs",
        [
          {
            id: HUMAN_TAB,
            title: "사람 탭",
            url: "https://example.com/",
            active: false,
            agent: false,
          },
          {
            id: AGENT_TAB,
            title: "에이전트 탭",
            url: "https://shopping.example.com/",
            active: true,
            agent: true,
          },
        ],
      ],
    ]);
    await expect(panel.getByTestId("agent-band")).toContainText(
      "에이전트가 쓰는 중이에요.",
    );
    await expect(panel.getByRole("button", { name: "새로고침" })).toBeDisabled();

    // 보기 전용 화면을 눌러도 입력을 보내지 않고 「직접 하시겠어요?」 만 묻는다. 뒤이어 보낸 탭 옮기기가 먼저 온 입력이 없음을 보인다.
    await panel.getByAltText("내 브라우저 화면").click();
    await expect(panel.getByTestId("takeover-confirm")).toBeVisible();
    await panel.getByRole("combobox", { name: "탭" }).selectOption(HUMAN_TAB);
    await expect
      .poll(() => ofType(fake.inputs, "tab").map((sent) => sent.id))
      .toEqual([AGENT_TAB, HUMAN_TAB]);
    expect(ofType(fake.inputs, "mouse")).toEqual([]);

    await hermes.releaseLongActivity();
    released = true;
    // 도구가 끝나면 단추는 사라지고 열린 패널은 남는다.
    await expect(page.getByTestId("agent-browser-open")).toHaveCount(0, {
      timeout: 30_000,
    });
    await expect(panel).toBeVisible();
  } finally {
    if (!released) {
      await hermes.releaseLongActivity();
    }
  }
});
