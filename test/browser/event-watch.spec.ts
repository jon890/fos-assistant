import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";

/**
 * 먼저 살펴보기 절의 「새 항목 살펴보기」 소절이다(ADR-20261105 / event-watch).
 *
 * <p>대역 카탈로그에는 사건 감지를 선언한 플러그인이 없다. 그래서 실제 Control Plane 과는 빈 목록과 거절만 왕복하고, 켜기와 상태 표시는
 * 응답 대역(`page.route`)으로 그린다. 켜기 조건의 판정은 backend 의 `EventWatchControllerTest` 가 본다. 모든 값은 합성이다.
 */

const AGENT_CODE = "browser";
const LIST_PATH = `/api/agents/${AGENT_CODE}/event-watches`;

type Watch = Record<string, unknown>;

function subsection(page: Page) {
  return page
    .getByRole("region", { name: "먼저 살펴보기", exact: true })
    .getByRole("region", { name: "새 항목 살펴보기" });
}

function watch(overrides: Watch = {}): Watch {
  return {
    connectorId: "demo-mail",
    title: "검사용 메일",
    noun: "메일",
    enabled: false,
    available: true,
    blockers: [],
    timezone: "Asia/Seoul",
    lastCheckedAt: null,
    lastResult: null,
    lastNewCount: 0,
    dailyLimitReached: false,
    failing: false,
    ...overrides,
  };
}

/** 목록 조회와 저장을 대역으로 답한다. 저장 요청의 본문을 모아 돌려준다. */
async function stubWatches(page: Page, listed: Watch[]) {
  const saved: unknown[] = [];
  await page.route(`**${LIST_PATH}`, (route: Route) =>
    route.fulfill({ json: listed }),
  );
  await page.route(`**${LIST_PATH}/*`, async (route: Route) => {
    const body = route.request().postDataJSON() as {
      enabled: boolean;
      timezone: string;
    };
    saved.push(body);
    await route.fulfill({
      json: watch({ enabled: body.enabled, timezone: body.timezone }),
    });
  });
  return saved;
}

test("지원하는 연결이 없으면 안내 한 줄을 보이고, 붙지 않은 연결과 틀린 형식은 거절한다", async ({
  page,
}) => {
  await page.goto(`/agents/${AGENT_CODE}`);

  await expect(subsection(page)).toContainText(
    "이 에이전트에 붙인 연결 가운데 새 항목 살펴보기를 지원하는 것이 없어요.",
  );

  const missing = await page.request.put(`${LIST_PATH}/demo-notes`, {
    data: { enabled: false, timezone: "" },
  });
  expect(missing.status()).toBe(404);
  expect(await missing.json()).toMatchObject({ code: "CONNECTOR_NOT_FOUND" });

  const malformed = await page.request.put(`${LIST_PATH}/Not_An_Id`, {
    data: { enabled: true, timezone: "" },
  });
  expect(malformed.status()).toBe(400);
  expect(await malformed.json()).toMatchObject({ code: "VALIDATION_FAILED" });
});

test("꺼진 연결을 켜고 저장하면 시간대와 함께 보내고 켜짐을 보인다", async ({
  page,
}) => {
  const saved = await stubWatches(page, [watch()]);
  await page.goto(`/agents/${AGENT_CODE}`);

  const item = subsection(page).getByTestId("event-watch");
  const toggle = item.getByRole("switch", {
    name: "검사용 메일 새 메일 살펴보기",
  });
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await expect(item.getByLabel("시간대", { exact: true })).toHaveValue(
    "Asia/Seoul",
  );

  await toggle.click();
  await clickAndWaitForResponse(
    page,
    item.getByRole("button", { name: "저장" }),
    "PUT",
    /\/api\/agents\/browser\/event-watches\/demo-mail$/,
  );

  expect(saved).toEqual([{ enabled: true, timezone: "Asia/Seoul" }]);
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(item).toContainText("아직 확인하지 않았어요");
});

test("오늘 상한과 다시 연결 필요, 켜지 못하는 까닭을 보인다", async ({
  page,
}) => {
  await stubWatches(page, [
    watch({
      connectorId: "demo-mail",
      enabled: true,
      lastCheckedAt: new Date().toISOString(),
      lastResult: "DAILY_LIMIT",
      dailyLimitReached: true,
      failing: true,
    }),
    watch({
      connectorId: "demo-other",
      title: "다른 메일",
      available: false,
      blockers: [
        { code: "EVENT_WATCH_ELSEWHERE", toolsets: [] },
        { code: "ISOLATED_EXECUTION_REQUIRED", toolsets: ["terminal"] },
      ],
    }),
  ]);
  await page.goto(`/agents/${AGENT_CODE}`);

  const items = subsection(page).getByTestId("event-watch");
  await expect(items).toHaveCount(2);
  const limited = items.nth(0);
  await expect(limited).toContainText("오늘 상한 도달");
  await expect(limited).toContainText("검사용 메일 다시 연결이 필요해요");
  await expect(
    limited.getByRole("link", { name: "외부 서비스 연결" }),
  ).toHaveAttribute("href", "/connections");

  const blocked = items.nth(1);
  await expect(
    blocked.getByRole("switch", { name: "다른 메일 새 메일 살펴보기" }),
  ).toBeDisabled();
  await expect(blocked).toContainText(
    "같은 다른 메일 연결을 다른 에이전트가 이미 살펴보고 있어요.",
  );
  await expect(blocked).toContainText("명령 실행 도구가 켜져 있어요.");
  await expect(blocked).toContainText(
    "격리된 실행 공간이 준비되기 전에는 켤 수 없어요.",
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
