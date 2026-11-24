import type { Page } from "../../node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { clickAndWaitForResponse, openAdminMenu } from "./helpers.ts";

/**
 * 관리자 「품질」 화면의 첫 칸(배포 뒤 확인 기록)이다. 확인 기록과 평가 사용자 상태는 대역으로 답한다.
 *
 * <p>실제 확인은 기능마다 수 분이 걸려 브라우저 검사에서 돌리지 않는다. 상태 전이와 판정은 backend 통합 테스트와 e2e 가 본다.
 */

const FAILED_CHECK = {
  publicId: "0195f1aa-0000-7000-8000-000000000001",
  trigger: "PIPELINE",
  status: "FAILED",
  startedAt: "2026-11-21T01:00:00Z",
  finishedAt: "2026-11-21T01:05:00Z",
  elapsedMs: 300_000,
  itemCount: 2,
  modelCallCount: 3,
  results: [
    { feature: "SEARCH", status: "FAILED", reasonCode: "NO_SOURCES", elapsedMs: 61_000, conversationPublicId: "0195f1aa-0000-7000-8000-0000000000c1", executionIds: [41] },
    { feature: "MEMORY_READ", status: "PASSED", reasonCode: null, elapsedMs: 12_000, conversationPublicId: null, executionIds: [] },
  ],
  environmentError: false,
};

const OLD_CHECK = {
  ...FAILED_CHECK,
  publicId: "0195f1aa-0000-7000-8000-000000000000",
  trigger: "ADMIN",
  startedAt: "2026-10-01T01:00:00Z",
  results: [{ feature: "IMAGE", status: "TIMED_OUT", reasonCode: "TIMED_OUT", elapsedMs: 600_000, conversationPublicId: null, executionIds: [] }],
};

const READY = { ready: true, hermesProfile: "evaluation", missing: [] };

/** 평가 사용자 상태와 확인 목록을 대역으로 답한다. 함수를 주면 요청마다 그때의 응답을 고른다. */
async function fakeQuality(
  page: Page,
  evaluation: () => unknown,
  checks: unknown[],
): Promise<void> {
  await page.route("**/api/admin/quality/evaluation-user", (route) =>
    route.request().method() === "GET"
      ? route.fulfill({ status: 200, json: evaluation() })
      : route.fallback(),
  );
  await page.route("**/api/admin/quality/checks", (route) =>
    route.request().method() === "GET"
      ? route.fulfill({ status: 200, json: { items: checks, nextCursor: null } })
      : route.fallback(),
  );
}

test("관리자 메뉴의 품질은 확인 기록을 최근 순으로 보이고 실패 기능의 까닭과 실행 트리 링크를 보인다", async ({ page }, testInfo) => {
  await fakeQuality(page, () => READY, [FAILED_CHECK, OLD_CHECK]);
  await page.goto("/admin/people");

  await (await openAdminMenu(page, testInfo)).getByRole("link", { name: "품질", exact: true }).click();
  await expect(page).toHaveURL(/\/admin\/quality$/);
  await expect(page).toHaveTitle(/품질/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("품질");

  const checks = page.getByTestId("quality-check");
  await expect(checks).toHaveCount(2);
  const failed = checks.nth(0);
  await expect(failed.getByText("실패", { exact: true })).toBeVisible();
  await expect(failed.getByText("배포")).toBeVisible();
  const features = failed.getByTestId("quality-feature");
  await expect(features.nth(0)).toContainText("검색과 출처");
  await expect(features.nth(0)).toContainText("답 끝 출처에 링크가 없어요");
  await expect(features.nth(0).getByRole("link", { name: "실행 #41" })).toHaveAttribute(
    "href",
    "/admin/quality/executions/41",
  );
  await expect(features.nth(1)).toContainText("기억 읽기");
  await expect(features.nth(1).getByRole("link")).toHaveCount(0);
  // 30일이 지나 링크를 비운 실패 줄은 실행 링크를 그리지 않는다.
  await expect(checks.nth(1).getByTestId("quality-feature").getByRole("link")).toHaveCount(0);
});

test("Control Plane 연결이 끊겨 실패한 확인은 「실패」 대신 「환경 오류」 로 보이고 기능에 그 까닭을 보인다", async ({ page }) => {
  const environment = {
    ...FAILED_CHECK,
    results: [
      { feature: "TOOL_DISCOVERY", status: "FAILED", reasonCode: "ENVIRONMENT", elapsedMs: 30_000, conversationPublicId: null, executionIds: [42] },
    ],
    environmentError: true,
  };
  await fakeQuality(page, () => READY, [environment]);
  await page.goto("/admin/quality");

  const row = page.getByTestId("quality-check");
  await expect(row.getByText("환경 오류", { exact: true })).toBeVisible();
  await expect(row.getByText("실패", { exact: true })).toHaveCount(0);
  const feature = row.getByTestId("quality-feature");
  await expect(feature).toContainText("도구 찾기");
  await expect(feature).toContainText("평가 비서의 Control Plane 연결이 끊겨 있었어요");
});

test("도는 확인을 다시 읽다 한 번 실패해도 다음 차례에 다시 읽어 결과를 보인다", async ({ page }) => {
  await page.clock.install();
  const running = { ...FAILED_CHECK, status: "RUNNING", finishedAt: null, elapsedMs: null, results: [] };
  let reads = 0;
  await page.route("**/api/admin/quality/evaluation-user", (route) => route.fulfill({ status: 200, json: READY }));
  await page.route("**/api/admin/quality/checks", (route) => {
    reads += 1;
    // 처음은 도는 확인, 두 번째 다시 읽기는 500, 세 번째부터 끝난 확인이다.
    if (reads === 1) return route.fulfill({ status: 200, json: { items: [running], nextCursor: null } });
    if (reads === 2) return route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR", message: "x" } });
    return route.fulfill({ status: 200, json: { items: [FAILED_CHECK], nextCursor: null } });
  });
  await page.goto("/admin/quality");

  const row = page.getByTestId("quality-check");
  await expect(row.getByText("확인 중", { exact: true })).toBeVisible();
  await page.clock.runFor(10_000);
  await expect.poll(() => reads).toBe(2);
  await expect(row.getByText("확인 중", { exact: true })).toBeVisible();
  await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);

  await page.clock.runFor(10_000);
  await expect(row.getByText("실패", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "확인 시작" })).toBeEnabled();
});

test("이미 도는 확인이 있으면 「다시 확인」 이 그 사실을 알린다", async ({ page }) => {
  await fakeQuality(page, () => READY, [FAILED_CHECK]);
  await page.route(`**/api/admin/quality/checks/${FAILED_CHECK.publicId}/retry`, (route) =>
    route.fulfill({ status: 200, json: { ...FAILED_CHECK, status: "FAILED" } }),
  );
  await page.goto("/admin/quality");

  const retry = page.getByTestId("quality-check").getByRole("button", { name: "다시 확인" });
  await clickAndWaitForResponse(page, retry, "POST", /\/api\/admin\/quality\/checks\/[^/]+\/retry$/);
  await expect(page.getByRole("status").filter({ hasText: "이미 확인이 돌고 있어요" })).toBeVisible();
});

test("평가 사용자가 없으면 기록 대신 준비 칸만 보이고, 준비하면 기록 칸이 나온다", async ({ page }) => {
  let prepared = false;
  await fakeQuality(page, () => (prepared ? READY : { ready: false, hermesProfile: null, missing: ["USER"] }), []);
  let body: unknown = null;
  await page.route("**/api/admin/quality/evaluation-user", (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    body = route.request().postDataJSON();
    prepared = true;
    return route.fulfill({ status: 200, json: READY });
  });
  await page.goto("/admin/quality");

  const panel = page.getByTestId("evaluation-user");
  await expect(panel).toContainText("먼저 평가 사용자를 준비해 주세요");
  await expect(page.getByRole("heading", { name: "배포 뒤 확인" })).toHaveCount(0);

  await panel.getByLabel("Hermes profile").fill("evaluation");
  await clickAndWaitForResponse(page, panel.getByRole("button", { name: "평가 사용자 준비" }), "POST", /\/api\/admin\/quality\/evaluation-user$/);

  expect(body).toEqual({ hermesProfile: "evaluation" });
  await expect(page.getByTestId("evaluation-user")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "배포 뒤 확인" })).toBeVisible();
  await expect(page.getByText("아직 확인 기록이 없어요")).toBeVisible();
});

test("평가 사용자 준비가 덜 끝났으면 빠진 것을 보인다", async ({ page }) => {
  await fakeQuality(page, () => ({ ready: false, hermesProfile: "evaluation", missing: ["POLICY_REGISTRATION_REQUIRED"] }), []);
  await page.goto("/admin/quality");

  const panel = page.getByTestId("evaluation-user");
  await expect(panel).toContainText("실행 공간과 사진 정책에 평가 profile 등록이 필요해요");
  await expect(panel.getByRole("button", { name: "다시 준비" })).toBeEnabled();
  await expect(page.getByRole("heading", { name: "배포 뒤 확인" })).toBeVisible();
});

test("요약의 이번 달 사용량 카드는 평가 사용자의 실행을 「평가」 한 줄로 따로 보인다", async ({ page }) => {
  await page.route("**/api/admin/overview/monthly", (route) =>
    route.fulfill({
      status: 200,
      json: {
        month: "2026-11",
        currency: "USD",
        totalExecutions: 10,
        subscriptionExecutions: 4,
        actualCostMicros: 0,
        incomplete: false,
        evaluation: { executions: 7, actualCostMicros: 0, incomplete: false },
      },
    }),
  );
  await page.goto("/admin");

  const monthly = page.getByRole("region", { name: "이번 달 사용량" });
  await expect(monthly.getByTestId("overview-monthly-total")).toHaveText("10건");
  await expect(monthly.getByTestId("overview-monthly-evaluation")).toContainText("평가");
  await expect(monthly.getByTestId("overview-monthly-evaluation")).toContainText("7건");
});
