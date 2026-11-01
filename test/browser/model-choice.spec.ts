import { CONVERSATION_URL, expect, setSession, test } from "./fixtures.ts";
import type {
  Locator,
  Page,
} from "../../web/node_modules/@playwright/test/index.js";

/** 실제로 디코딩되는 1x1 PNG 다. 저장소에 이미지를 넣지 않으려고 바이트로 만들어 쓴다. */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64",
);

/** 입력칸이 이보다 좁아지면 한 줄에 몇 글자 들어가지 않는다. */
const MIN_TEXTAREA_WIDTH = 160;

type Tier = {
  tier: string;
  label: string;
  provider: string | null;
  model: string | null;
  reasoningEffort: string | null;
};

/**
 * 관리자가 세 칸만 채운 그룹이다. 칸 뒤의 모델 이름을 일부러 실어, 화면이 역할과 상관없이 그 값을 그리지 않는지 본다.
 * `MEMBER` 역할에게는 Control Plane 이 이 값을 비워 보낸다.
 */
const FILLED_TIERS: Tier[] = [
  {
    tier: "INSTANT",
    label: "Instant",
    provider: "openai-codex",
    model: "example-fast",
    reasoningEffort: "low",
  },
  {
    tier: "MEDIUM",
    label: "Medium",
    provider: "openai-codex",
    model: "example-balanced",
    reasoningEffort: "medium",
  },
  {
    tier: "HIGH",
    label: "High",
    provider: "openai-codex",
    model: "example-deep",
    reasoningEffort: "high",
  },
];

function composer(page: Page): Locator {
  return page.getByRole("textbox", { name: "메시지" });
}

function trigger(page: Page): Locator {
  return page.getByTestId("model-tier-picker");
}

function slider(page: Page): Locator {
  return page.getByTestId("model-tier-slider");
}

function stop(page: Page, label: string): Locator {
  return slider(page).getByRole("radio", { name: label, exact: true });
}

function isConversationList(url: URL): boolean {
  return url.pathname === "/api/chat/conversations";
}

function isTierSave(url: URL): boolean {
  return /\/api\/chat\/conversations\/[^/]+\/model-tier$/.test(url.pathname);
}

/**
 * 칸 목록을 읽는 응답의 칸과 기본 칸을 바꾼다.
 *
 * <p>검사용 그룹은 칸을 채우지 않아 그대로면 단추가 없다. 그룹 정의를 저장하면 같은 shard 의 다른 검사에 번지므로 응답만
 * 바꾼다. 칸을 고르면 그 사용자의 기본 칸이 바뀌어 같은 사용자를 쓰는 다음 검사의 처음 위치가 흔들리므로, 사용자 기본 칸도
 * 기본으로는 비워 보낸다. 고른 칸이 다음 새 대화에 남는지 보는 검사만 `userDefault: "server"` 로 실제 값을 쓴다.
 */
async function serveTiers(
  page: Page,
  tiers: Tier[],
  {
    groupDefaultTier = "MEDIUM",
    userDefault = null,
  }: { groupDefaultTier?: string | null; userDefault?: "server" | null } = {},
): Promise<void> {
  await page.route(
    (url) => url.pathname === "/api/chat/model-tiers",
    async (route) => {
      const response = await route.fetch();
      const body = (await response.json()) as Record<string, unknown>;
      await route.fulfill({
        response,
        json: {
          ...body,
          tiers,
          groupDefaultTier,
          ...(userDefault === "server" ? {} : { userDefaultTier: null }),
        },
      });
    },
  );
}

/** 칸 이름만 담은 목록이다. `MEMBER` 역할이 받는 모양이다. */
function namesOnly(tiers: Tier[]): Tier[] {
  return tiers.map((item) => ({
    ...item,
    provider: null,
    model: null,
    reasoningEffort: null,
  }));
}

async function openSlider(page: Page): Promise<void> {
  await expect(trigger(page)).toBeEnabled();
  await trigger(page).click();
  await expect(slider(page)).toBeVisible();
}

/** 칸을 고르고 저장 응답까지 기다린다. */
async function chooseAndWait(page: Page, label: string): Promise<void> {
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      isTierSave(new URL(response.url())),
  );
  await stop(page, label).check();
  expect((await saved).ok(), "칸 저장 응답").toBeTruthy();
}

/** 빈 대화를 API 로 만든다. 화면을 거치지 않아 목록 읽기가 검사 도중에 끼어들지 않는다. */
async function createConversation(page: Page): Promise<string> {
  const created = await page.request.post("/api/chat/conversations", {
    data: { agentCode: "browser" },
  });
  expect(
    created.ok(),
    `빈 대화를 만들지 못했다: ${created.status()}`,
  ).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/**
 * 칸 저장을 Control Plane 에 보내지 않고 저장된 대화 한 줄로 답한다.
 *
 * <p>칸을 고르면 그 사용자의 기본 칸도 바뀐다. 기본 검사 사용자는 같은 shard 의 다른 검사(사용량의 effort 표시 등)가 함께
 * 쓰므로, 그 사용자의 기본 칸을 남기지 않으려고 저장만 대역으로 둔다. 실제 저장과 기본 칸 갱신은 따로 만든 사용자로
 * 「다음 새 대화」 검사가 본다. `gate` 를 주면 그것이 풀릴 때까지 답하지 않는다.
 */
async function fakeTierSaves(page: Page, gate?: Promise<void>): Promise<void> {
  await page.route(
    (url) => isTierSave(url),
    async (route) => {
      if (route.request().method() !== "PUT") return route.fallback();
      if (gate) await gate;
      const { tier } = route.request().postDataJSON() as { tier: string };
      await route.fulfill({
        json: {
          id: new URL(route.request().url()).pathname.split("/").at(-2),
          title: "새 대화",
          agentCode: "browser",
          agentName: "브라우저 비서",
          updatedAt: new Date().toISOString(),
          modelSelectionMode: "TIER",
          modelTier: tier,
          purpose: "CHAT",
          taskId: null,
          taskTitle: null,
        },
      });
    },
  );
}

async function sendAndWait(page: Page, text: string): Promise<void> {
  await composer(page).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page.getByTestId("user-message").last()).toContainText(text);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({
    timeout: 30_000,
  });
  // 답이 끝난 뒤에 돌려준다. 답이 오는 동안 이어 보낸 글은 대기 메시지로 쌓인다.
  await expect(
    page.getByTestId("composer-shell").getByRole("button", { name: "중지" }),
  ).toHaveCount(0, { timeout: 30_000 });
}

async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(
    () =>
      document.documentElement.scrollWidth -
      document.documentElement.clientWidth,
  );
  expect(overflow, "페이지가 가로로 밀린 폭(px)").toBeLessThanOrEqual(0);
}

test("에이전트가 없는 새 대화는 칸을 읽지 않고 모델 단추를 막는다", async ({
  page,
}) => {
  const tierRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/chat/model-tiers")
      tierRequests.push(request.url());
  });
  await page.route("**/api/agents", (route) => route.fulfill({ json: [] }));

  await page.goto("/");
  await expect(trigger(page)).toBeDisabled();
  expect(tierRequests).toEqual([]);
});

test("관리자가 칸을 하나도 채우지 않았으면 모델 단추를 그리지 않는다", async ({
  page,
}) => {
  await serveTiers(page, [], { groupDefaultTier: null });
  const read = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/chat/model-tiers",
  );
  await page.goto("/");
  await read;

  await expect(composer(page)).toBeVisible();
  await expect(trigger(page)).toHaveCount(0);
});

test("슬라이더에는 채운 칸의 이름만 보이고 고급 직접 선택과 내 기본값 설정이 없다", async ({
  page,
}) => {
  await serveTiers(page, FILLED_TIERS);
  await page.goto("/");

  await expect(trigger(page)).toHaveText("Medium");
  await openSlider(page);
  await expect(slider(page).getByRole("radio")).toHaveCount(3);
  for (const label of ["Instant", "Medium", "High"])
    await expect(stop(page, label)).toBeVisible();
  // 관리자가 비운 칸은 보이지 않는다.
  await expect(stop(page, "Extra High")).toHaveCount(0);
  await expect(stop(page, "Pro")).toHaveCount(0);
  await expect(stop(page, "Medium")).toBeChecked();
  // 칸 뒤의 모델은 관리자에게도 대화 화면에서는 그리지 않는다(ADR-063).
  await expect(slider(page)).not.toContainText("example-");
  await expect(slider(page).getByRole("combobox")).toHaveCount(0);
  await expect(page.getByText("고급 직접 선택")).toHaveCount(0);
  await expect(page.getByText("내 기본값")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "그룹 모델 설정" }),
  ).toHaveCount(0);
  await expect(page.getByTestId("model-tier-settings")).toHaveCount(0);
});

test("슬라이더로 고른 칸이 대화에 남고 다음 새 대화가 그 칸에서 시작한다", async ({
  context,
  page,
  isolatedMember,
}) => {
  // 실제로 저장해 사용자 기본 칸이 바뀌는지 본다. 다른 검사에 기본 칸을 남기지 않도록 따로 만든 사용자와 에이전트를 쓴다.
  await setSession(context, isolatedMember);
  const agent = await page.request.post("/api/agents", {
    data: { name: "슬라이더 검사 비서" },
  });
  expect(agent.status()).toBe(201);
  const { code: agentCode } = (await agent.json()) as { code: string };
  try {
    await expectChosenTierCarriesOver(page);
  } finally {
    const removed = await page.request.delete(`/api/agents/${agentCode}`);
    expect(removed.status()).toBe(204);
  }
});

async function expectChosenTierCarriesOver(page: Page): Promise<void> {
  await serveTiers(page, namesOnly(FILLED_TIERS), { userDefault: "server" });
  const bodies: unknown[] = [];
  page.on("request", (request) => {
    if (request.method() === "PUT" && isTierSave(new URL(request.url())))
      bodies.push(request.postDataJSON());
  });
  await page.goto("/");

  // 고른 적이 없으면 그룹 기본 칸에서 시작한다.
  await expect(trigger(page)).toHaveText("Medium");
  const target = "High";
  await openSlider(page);
  await chooseAndWait(page, target);
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(trigger(page)).toHaveText(target);
  expect(bodies).toEqual([{ mode: "TIER", tier: "HIGH" }]);

  await page.reload();
  await expect(trigger(page)).toHaveText(target);

  // 고른 칸은 사용자의 기본 칸이 되어 다음 새 대화의 처음 위치다.
  await page.goto("/");
  await expect(trigger(page)).toHaveText(target);
  await openSlider(page);
  await expect(stop(page, target)).toBeChecked();
}

test("방향키로 칸을 옮기면 옮긴 칸이 저장된다", async ({ page }) => {
  await serveTiers(page, namesOnly(FILLED_TIERS));
  await fakeTierSaves(page);
  const conversationId = await createConversation(page);
  await page.goto(`/chat/${conversationId}`);

  await openSlider(page);
  await stop(page, "Medium").focus();
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      isTierSave(new URL(response.url())),
  );
  await page.keyboard.press("ArrowLeft");
  const response = await saved;
  expect(response.ok()).toBeTruthy();
  expect(response.request().postDataJSON()).toEqual({
    mode: "TIER",
    tier: "INSTANT",
  });
  await expect(stop(page, "Instant")).toBeChecked();
  await expect(trigger(page)).toHaveText("Instant");
});

test("칸을 저장하는 동안 보내기를 막는다", async ({ page }) => {
  await serveTiers(page, namesOnly(FILLED_TIERS));
  const conversationId = await createConversation(page);
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await fakeTierSaves(page, gate);
  await page.goto(`/chat/${conversationId}`);
  await composer(page).fill("저장 중에 보내기");

  await openSlider(page);
  await stop(page, "High").check();
  // 저장이 끝나기 전에 보낸 메시지가 이전 칸으로 돌지 않게 한다.
  await expect(page.getByRole("button", { name: "보내기" })).toBeDisabled();
  release();
  await expect(trigger(page)).toHaveText("High");
  await expect(page.getByRole("button", { name: "보내기" })).toBeEnabled();
});

test("저장이 실패하면 단추가 이전 칸으로 돌아가고 알린다", async ({ page }) => {
  await serveTiers(page, namesOnly(FILLED_TIERS));
  const conversationId = await createConversation(page);
  await page.route(
    (url) => isTierSave(url),
    (route) =>
      route.fulfill({
        status: 500,
        json: { code: "INTERNAL_ERROR", message: "요청을 처리하지 못했어요." },
      }),
  );
  await page.goto(`/chat/${conversationId}`);

  await openSlider(page);
  await stop(page, "High").check();

  await expect(page.getByTestId("model-picker-error")).toHaveText(
    "모델 단계를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await expect(page.getByTestId("model-picker-error")).toHaveAttribute(
    "role",
    "alert",
  );
  await expect(trigger(page)).toHaveText("Medium");
  await expect(stop(page, "Medium")).toBeChecked();
});

test("빈 대화를 만들지 못하면 입력창의 안내 하나만 보인다", async ({
  page,
}) => {
  await serveTiers(page, FILLED_TIERS);
  await page.route(
    (url) => isConversationList(url),
    (route) =>
      route.request().method() === "POST"
        ? route.fulfill({
            status: 500,
            json: {
              code: "INTERNAL_ERROR",
              message: "요청을 처리하지 못했어요.",
            },
          })
        : route.fallback(),
  );
  await page.goto("/");

  await openSlider(page);
  await stop(page, "High").check();

  await expect(page.getByTestId("attachment-notice")).toHaveText(
    "대화를 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  );
  await expect(page.getByTestId("model-picker-error")).toHaveCount(0);
  await expect(trigger(page)).toHaveText("Medium");
});

test("새 대화에서 칸을 먼저 고르면 에이전트 카드가 잠기고 사진을 올려 보낼 수 있다", async ({
  page,
}, testInfo) => {
  // 슬롯 대기만 30초까지 허용된다. 응답 45초와 칸 선택·메시지 전송 시간을 따로 확보한다.
  test.setTimeout(90_000);
  await serveTiers(page, FILLED_TIERS);
  await fakeTierSaves(page);
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    const response = await route.fetch();
    // 기존 UI assertion의 기본 5초보다 늦게 실제 응답 본문을 전달한다.
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    await route.fulfill({ response });
  });
  await page.goto("/");
  const browserCard = page.getByRole("radio", { name: "브라우저 비서" });
  await expect(browserCard).toHaveAttribute("aria-checked", "true");

  await openSlider(page);
  await chooseAndWait(page, "Instant");
  await page.keyboard.press("Escape");

  // 빈 대화가 생겨 에이전트가 정해졌다. 메시지가 없는 동안은 새 대화 화면 모양 그대로다.
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(trigger(page)).toHaveText("Instant");
  await expect(
    page.getByRole("radio", { name: "성격 비서", exact: true }),
  ).toBeDisabled();

  const uploaded = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/chat\/conversations\/[^/]+\/attachments$/.test(response.url()),
    { timeout: 45_000 },
  );
  await page
    .getByTestId("attachment-input")
    .setInputFiles([
      { name: "tier-first.png", mimeType: "image/png", buffer: PNG_1X1 },
    ]);
  await expect(
    page.getByTestId("attachment-previews").locator("> div"),
  ).toHaveCount(1);
  // 헤더 도착 뒤 본문까지 확인한다. 오류 응답도 uploading을 지우므로 완료 표시만으로 성공을 판단하지 않는다.
  const response = await uploaded;
  const attachment = (await response.json()) as { id?: number };
  expect(response.status(), "사진 업로드 응답 상태").toBe(200);
  expect(attachment.id, "사진 업로드 응답의 첨부 식별자").toBeGreaterThan(0);
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toHaveText("");

  await sendAndWait(page, `칸 먼저 고르기 검사 ${testInfo.project.name}`);
  await expect(
    page.getByTestId("user-message").last().getByTestId("message-attachment"),
  ).toHaveCount(1);
  await expect(page.getByTestId("user-message")).toHaveCount(1);
});

test("새 대화에서 칸을 먼저 고른 뒤 사진 업로드가 실패하면 오류를 알리고 사진을 빼고 보낸다", async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000);
  await serveTiers(page, FILLED_TIERS);
  await fakeTierSaves(page);
  await page.route("**/api/chat/conversations/*/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    await new Promise((resolve) => setTimeout(resolve, 5_500));
    await route.fulfill({
      status: 502,
      json: { code: "INTERNAL_ERROR", message: "사진을 올리지 못했어요." },
    });
  });
  await page.goto("/");
  await openSlider(page);
  await chooseAndWait(page, "Instant");
  await page.keyboard.press("Escape");
  await expect(trigger(page)).toHaveText("Instant");

  const uploaded = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/chat\/conversations\/[^/]+\/attachments$/.test(response.url()),
    { timeout: 45_000 },
  );
  await page.getByTestId("attachment-input").setInputFiles([
    {
      name: "tier-first-failed.png",
      mimeType: "image/png",
      buffer: PNG_1X1,
    },
  ]);
  await expect(page.getByTestId("attachment-uploading")).toBeVisible();
  const response = await uploaded;
  expect(response.status()).toBe(502);
  expect((await response.json()).code).toBe("INTERNAL_ERROR");
  await expect(page.getByTestId("attachment-uploading")).toHaveCount(0);
  await expect(page.getByTestId("attachment-previews")).toContainText(
    "사진을 올리지 못했어요.",
  );
  await page.getByRole("button", { name: "사진 지우기" }).click();
  await expect(page.getByTestId("attachment-previews")).toHaveCount(0);
  await sendAndWait(
    page,
    `칸 먼저 고르기 업로드 실패 검사 ${testInfo.project.name}`,
  );
  await expect(page.getByTestId("user-message")).toHaveCount(1);
  await expect(
    page.getByTestId("user-message").last().getByTestId("message-attachment"),
  ).toHaveCount(0);
});

test("좁은 폭에서도 슬라이더가 화면 안에 열리고 입력칸 폭이 남는다", async ({
  page,
}, testInfo) => {
  await serveTiers(page, FILLED_TIERS);
  await fakeTierSaves(page);
  await page.goto("/");

  await openSlider(page);
  // 슬라이더는 모델 단추 바로 위에 열린다. 줄인 움직임 설정도 100ms 동안 자리로 옮겨 가므로 자리를 잡을 때까지 본다.
  await expect
    .poll(
      async () => {
        const box = await slider(page).boundingBox();
        const anchor = await trigger(page).boundingBox();
        if (box === null || anchor === null) return Number.NaN;
        return anchor.y - (box.y + box.height);
      },
      { message: "단추 위쪽 끝과 슬라이더 아래쪽 끝의 틈(px)" },
    )
    // 틈은 sideOffset 6px 이다. 옮겨 가는 도중의 자리를 재지 않도록 거의 닿을 때까지 기다린다.
    .toBeLessThan(8);
  const viewport = page.viewportSize();
  const box = await slider(page).boundingBox();
  const anchor = await trigger(page).boundingBox();
  expect(
    anchor!.y - (box!.y + box!.height),
    "단추와의 틈(px)",
  ).toBeGreaterThanOrEqual(0);
  expect(box!.y, "슬라이더 위쪽 끝(px)").toBeGreaterThanOrEqual(0);
  expect(box!.x, "슬라이더 왼쪽 끝(px)").toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width, "슬라이더 오른쪽 끝(px)").toBeLessThanOrEqual(
    viewport!.width,
  );
  await expectNoHorizontalScroll(page);
  await page.screenshot({
    path: testInfo.outputPath(
      `model-tier-slider-open-${testInfo.project.name}.png`,
    ),
  });

  await chooseAndWait(page, "High");
  await page.keyboard.press("Escape");
  await expect(slider(page)).toHaveCount(0);
  await expect(trigger(page)).toHaveText("High");
  await expectNoHorizontalScroll(page);
  const textarea = await composer(page).boundingBox();
  expect(textarea?.width ?? 0, "입력칸 폭(px)").toBeGreaterThanOrEqual(
    MIN_TEXTAREA_WIDTH,
  );
  await page.screenshot({
    path: testInfo.outputPath(
      `model-tier-slider-chosen-${testInfo.project.name}.png`,
    ),
  });
});

test("대화 목록이 오기 전에는 이미 있는 대화의 모델 단추를 막는다", async ({
  page,
}) => {
  await serveTiers(page, FILLED_TIERS);
  const conversationId = await createConversation(page);
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(
    (url) => isConversationList(url),
    async (route) => {
      if (route.request().method() === "GET") await gate;
      await route.fallback();
    },
  );
  const agentsRead = page.waitForResponse("**/api/agents");
  await page.goto(`/chat/${conversationId}`);
  const agentsResponse = await agentsRead;
  expect(agentsResponse.ok()).toBeTruthy();
  await agentsResponse.finished();
  expect(await agentsResponse.json()).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ code: "browser", acceptsAttachments: true }),
    ]),
  );

  // 에이전트 목록이 와도 기존 대화의 에이전트는 아직 모른다. 첫 에이전트의 사진 도구를 대신 주지 않는다.
  await composer(page).fill("목록을 기다리며 쓴 글");
  await composer(page).evaluate((node) =>
    node.setAttribute("data-kept", "true"),
  );
  await expect(page.getByTestId("attachment-input")).toHaveCount(0);
  // 적힌 칸을 모르는 채 고르게 하지 않는다.
  await expect(trigger(page)).toBeDisabled();
  release();
  await expect(trigger(page)).toBeEnabled();
  await expect(page.getByTestId("attachment-input")).toBeAttached();
  await expect(composer(page)).toHaveValue("목록을 기다리며 쓴 글");
  await expect(composer(page)).toHaveAttribute("data-kept", "true");
  await expect(trigger(page)).toHaveText("Medium");
});

test("칸 저장 때문에 버린 대화 목록 응답은 한 번 더 읽는다", async ({
  page,
}) => {
  await serveTiers(page, FILLED_TIERS);
  await fakeTierSaves(page);
  // 빈 대화를 만든 뒤에 나가는 목록 읽기 하나를 저장이 끝날 때까지 붙잡는다. 첫 화면의 목록 읽기는 붙잡지 않는다.
  let created = false;
  let taken = false;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let markHeld!: () => void;
  const held = new Promise<void>((resolve) => {
    markHeld = resolve;
  });
  await page.route(
    (url) => isConversationList(url),
    async (route) => {
      const method = route.request().method();
      if (method === "POST") created = true;
      if (method === "GET" && created && !taken) {
        taken = true;
        markHeld();
        await gate;
      }
      await route.fallback();
    },
  );
  await page.goto("/");

  await openSlider(page);
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === "PUT" &&
      isTierSave(new URL(response.url())),
  );
  await stop(page, "Instant").check();
  await held;
  expect((await saved).ok(), "칸 저장 응답").toBeTruthy();

  // 붙잡은 응답은 저장보다 먼저 나가 버려진다. 버린 채 두면 다른 줄의 제목과 순서가 옛 값으로 남는다.
  const again = page.waitForRequest(
    (request) =>
      request.method() === "GET" && isConversationList(new URL(request.url())),
  );
  release();
  await again;
  await expect(trigger(page)).toHaveText("Instant");
  await expect(page.getByTestId("model-picker-error")).toHaveCount(0);
});
