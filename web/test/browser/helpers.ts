import { createHash } from "node:crypto";
import type {
  Locator,
  Page,
  TestInfo,
} from "../../node_modules/@playwright/test/index.js";
import playwright from "../../node_modules/@playwright/test/index.js";
import { SignJWT } from "../../node_modules/jose/dist/webapi/index.js";
import {
  CONTROL_PLANE_BASE_URL,
  JWT_SECRET,
  RUN_ID,
  TEST_EMAIL,
} from "./settings.ts";

const { expect } = playwright;

/** 같은 검사의 준비와 정리가 같은 주소를 쓰되, 폭·반복·실행이 바뀌면 새 사용자를 만든다. */
export function isolatedUser(testInfo: TestInfo, prefix: string) {
  const namespace = createHash("sha256")
    .update(
      `${RUN_ID}:${testInfo.testId}:${testInfo.project.name}:${testInfo.repeatEachIndex}:${testInfo.retry}`,
    )
    .digest("hex")
    .slice(0, 16);
  return {
    email: `${prefix}-${namespace}@example.com`,
    name: "브라우저 검사 사용자",
  };
}

/** HTTP 응답의 머리만 도착한 상태와 본문까지 읽힌 상태를 구분한다. SSE에는 쓰지 않는다. */
export async function clickAndWaitForResponse(
  page: Page,
  target: Locator,
  method: string,
  pathname: RegExp,
) {
  const responsePromise = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === method &&
      pathname.test(new URL(candidate.url()).pathname),
  );
  await target.click();
  const response = await responsePromise;
  expect(await response.finished(), "상태를 바꾸는 응답이 끊겼다").toBeNull();
  expect(
    response.ok(),
    `${method} 요청이 실패했다: ${response.status()}`,
  ).toBeTruthy();
  return response;
}

export const FIXED_BROWSER_NOW = new Date("2026-10-07T03:00:00Z");

/** 날짜만 고정하고 폴링과 애니메이션의 타이머는 계속 흐르게 한다. */
export async function fixBrowserTime(page: Page, at = FIXED_BROWSER_NOW) {
  await page.clock.setFixedTime(at);
}

/** 주소 변경과 조작할 단추의 준비를 확인한 뒤, 진행 중인 화면 전환의 스냅샷이 사라질 때까지 기다린다. */
export async function waitForViewTransition(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every(
        (animation) =>
          !(
            animation.effect instanceof KeyframeEffect &&
            animation.effect.pseudoElement?.startsWith("::view-transition") &&
            animation.playState !== "finished"
          ),
      ),
  );
}

/** `GET /api/admin/model-tiers` 가 내는 그룹 칸 정의다. */
export type GroupModelTiers = {
  tiers: {
    tier: string;
    provider: string | null;
    model: string | null;
    reasoningEffort: string | null;
  }[];
  groupDefaultTier: string | null;
};

/** 관리자 세션으로 그룹 칸 정의를 읽는다. 바꾸는 검사는 이 값을 들고 있다가 끝에 되돌린다. */
export async function readGroupModelTiers(page: Page): Promise<GroupModelTiers> {
  const response = await page.request.get("/api/admin/model-tiers");
  expect(response.ok(), `칸 정의를 읽지 못했다: ${response.status()}`).toBeTruthy();
  return (await response.json()) as GroupModelTiers;
}

/** 관리자 세션으로 그룹 칸 정의와 그룹 기본 칸을 저장한다. 칸 정의는 그대로 두고 기본 칸만 바꿀 때도 쓴다. */
export async function saveGroupModelTiers(
  page: Page,
  tiers: GroupModelTiers["tiers"],
  defaultTier: string | null,
): Promise<void> {
  const response = await page.request.put("/api/chat/model-tiers/group", {
    data: {
      tiers: tiers.map(({ tier, provider, model, reasoningEffort }) => ({
        tier,
        provider,
        model,
        reasoningEffort,
      })),
      defaultTier,
    },
  });
  expect(response.ok(), `칸 정의를 저장하지 못했다: ${response.status()} ${await response.text()}`).toBeTruthy();
}

/** 관리자 세션으로 그룹 칸 정의를 읽어 그 칸이 가리키는 provider 를 돌려준다. 빈 칸이면 실패한다. */
export async function filledTierProvider(page: Page, tier: string): Promise<string> {
  const { tiers } = await readGroupModelTiers(page);
  const provider = tiers.find((it) => it.tier === tier)?.provider;
  expect(provider, `${tier} 칸이 비어 있다`).toBeTruthy();
  return provider!;
}

/**
 * 입력창 위 「할 일 N건」 줄을 눌러 옆 패널의 「할 일」 탭을 연다. 승인 카드와 넘기기 카드는 그 탭에만 있다.
 * 이미 열려 있으면 그대로 둔다(새 승인이 저절로 연 경우).
 */
export async function openTodo(page: Page) {
  const tab = page.getByTestId("todo-tab-todo");
  await expect(page.getByTestId("todo-summary").or(tab).first()).toBeVisible();
  if (!(await tab.isVisible())) await page.getByTestId("todo-summary").click();
  await expect(tab).toBeVisible();
}

/**
 * 사이드바 맨 아래의 이름 메뉴를 연다. 좁은 폭이면 서랍을 먼저 연다.
 *
 * <p>설정, 관리자 화면, 화면 밝기, 로그아웃이 이 메뉴에 있다(docs/features/app-shell/spec.md 의 REQ3).
 */
export async function openAccountMenu(page: Page, testInfo: TestInfo) {
  const trigger = page.getByRole("button", { name: "계정 메뉴" });
  if (testInfo.project.name === "mobile" && !(await trigger.isVisible())) {
    await page.getByRole("button", { name: "사이드바 열기" }).click();
  }
  await trigger.click();
  await expect(page.getByRole("menu")).toBeVisible();
}

/**
 * 새 대화 화면의 에이전트 이름표다. 넓은 화면은 대화 영역 머리 줄, 좁은 폭은 위 막대 가운데에 있어 보이는 쪽을 쓴다.
 */
export function startAgentTag(page: Page): Locator {
  return page.locator('[data-testid="start-agent-tag"]:visible');
}

/** 새 대화 화면이 그 에이전트로 열렸는지 본다. 새 대화는 늘 기본 비서다(docs/features/assistant-hub/spec.md 의 REQ4) */
export async function expectStartAgent(page: Page, name: string) {
  await expect(startAgentTag(page).getByTestId("start-agent-name")).toHaveText(
    name,
  );
}

/** 그 사용자의 메일로 서명한 Control Plane 토큰으로 test-support 경로를 부른다. */
async function testSupport(
  email: string,
  method: string,
  path: string,
  body?: unknown,
): Promise<Response> {
  const token = await new SignJWT({})
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(email)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
  return fetch(`${CONTROL_PLANE_BASE_URL}/api/v1/test-support/${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/**
 * 그 사용자의 기본 비서를 그 에이전트로 둔다. 검사 사용자는 허용 목록 없이 들어와 기본 비서가 없고, 새 대화는 기본 비서로만
 * 열린다(docs/features/assistant-hub/spec.md 의 REQ4).
 */
export async function usePrimary(email: string, agentCode: string) {
  const response = await testSupport(email, "PUT", `primary-agent/${agentCode}`);
  expect(response.status, `${agentCode} 를 기본 비서로 두지 못했다`).toBe(200);
}

/** 그 사용자의 기본 비서를 없앤다. 기본 비서는 지울 수 없으므로 검사가 만든 에이전트를 지우기 전에 부른다. */
export async function clearPrimary(email: string) {
  const response = await testSupport(email, "DELETE", "primary-agent");
  expect(response.status, "기본 비서를 없애지 못했다").toBe(200);
}

/**
 * test-support 의 대화 만들기가 잠시 바꾼 기본 비서와 에이전트 주인을 되돌린다. 공통 fixture 가 검사마다 부른다.
 * 바꾼 것이 없으면 그대로다.
 */
export async function restorePrimaries() {
  const response = await testSupport(
    TEST_EMAIL,
    "POST",
    "primary-agent/restore",
  );
  expect(response.status, "잠시 바꾼 기본 비서를 되돌리지 못했다").toBe(200);
}

/**
 * 그 사용자의 실행 하나를 그 에이전트가 돈 것으로 바꾼다. `@` 맡기기를 없애기 전에 남은 맡김과 같은 꼴을 만든다
 * (docs/features/assistant-hub/spec.md 의 REQ6).
 */
export async function relabelExecution(
  email: string,
  executionId: number,
  agentCode: string,
) {
  const response = await testSupport(
    email,
    "PUT",
    `chat/executions/${executionId}/agent/${agentCode}`,
  );
  expect(response.status, `실행 ${executionId} 을 바꾸지 못했다`).toBe(200);
}

/**
 * 그 에이전트와의 빈 대화를 만들고 그 대화를 연다. test-support 경로가 그 에이전트를 그 사용자의 기본 비서로 잠시 둔다.
 *
 * <p>새 대화는 기본 비서로만 열리고 비서가 아닌 대화는 읽기 전용이라(docs/features/assistant-hub/spec.md 의 REQ4, REQ5) 다른
 * 에이전트의 입력창을 보는 검사가 쓴다. 공통 fixture 가 검사가 끝나면 {@link restorePrimaries} 로 되돌린다.
 */
export async function openConversationWith(
  page: Page,
  agentCode: string,
  email = TEST_EMAIL,
): Promise<string> {
  const conversationId = await seedConversation(agentCode, email);
  await page.goto(`/chat/${conversationId}`);
  return conversationId;
}

/** {@link openConversationWith} 의 대화를 만들기만 하고 공개 식별자를 돌려준다. 화면을 열기 전에 route 를 거는 검사가 쓴다. */
export async function seedConversation(
  agentCode: string,
  email = TEST_EMAIL,
): Promise<string> {
  const response = await testSupport(email, "POST", "chat/conversations", {
    agentCode,
  });
  expect(
    response.status,
    `${agentCode} 와의 대화를 만들지 못했다: ${await response.clone().text()}`,
  ).toBe(200);
  const { conversationId } = (await response.json()) as {
    conversationId: string;
  };
  return conversationId;
}

/**
 * 관리자 메뉴를 찾는다. 휴대폰 프로젝트에서는 메뉴가 서랍 안에 있어 머리의 단추로 먼저 연다.
 *
 * <p>넓은 화면은 메뉴가 늘 보인다. 서랍이 닫힌 휴대폰에서는 접근성 트리에 메뉴가 없다.
 */
export async function openAdminMenu(page: Page, testInfo: TestInfo): Promise<Locator> {
  if (testInfo.project.name === "mobile") {
    await page.getByRole("button", { name: "관리자 메뉴 열기" }).click();
  }
  const menu = page.getByRole("navigation", { name: "관리자 메뉴" });
  await expect(menu).toBeVisible();
  return menu;
}
