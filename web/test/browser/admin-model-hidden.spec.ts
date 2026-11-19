import { expect, setSession, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

/**
 * 관리자 「모델」 화면의 숨김 절을 본다. 에이전트는 모델을 갖지 않으므로 에이전트를 고르지 않고 그룹 전체의 후보를
 * 읽는다(ADR-20261101 / model-setting).
 */

/** 대역 catalog 에 있는 모델이다. 다른 검사가 실제 실행에 쓰지 않는 것을 숨긴다. */
const HIDDEN_MODEL = "example-deep";
const KEPT_MODEL = "example-model-mini";

async function clearHidden(page: Page): Promise<void> {
  const hidden = await page.request.put("/api/admin/model-hidden", {
    data: { entries: [] },
  });
  expect(hidden.ok(), `숨김을 비우지 못했다: ${hidden.status()}`).toBeTruthy();
}

type Catalog = { catalog: { providers: { models: string[] }[] } | null };

/** 관리자가 칸에 고를 수 있는 그룹 모델 목록이다. 숨김이 적용된 목록이라 숨긴 모델이 빠진다. */
async function groupCatalog(page: Page): Promise<Catalog> {
  const response = await page.request.get("/api/admin/model-catalog");
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as Catalog;
}

test("관리자가 모델을 숨기면 고를 수 있는 목록에서 빠지고 새로 고쳐도 숨김이 남는다", async ({ page }) => {
  await clearHidden(page);
  try {
    await page.goto("/admin/models");
    const section = page.getByRole("region", { name: "모델 숨김" });
    const save = section.getByRole("button", { name: "숨김 저장" });
    await expect(save).toBeDisabled();
    await section.getByRole("checkbox", { name: `${HIDDEN_MODEL} 숨기기` }).check();
    await save.click();
    await expect(section.getByRole("status")).toHaveText("숨김 설정을 저장했어요.");

    const { catalog } = await groupCatalog(page);
    expect(catalog?.providers[0]?.models).not.toContain(HIDDEN_MODEL);
    expect(catalog?.providers[0]?.models).toContain(KEPT_MODEL);

    await page.reload();
    await expect(section.getByRole("checkbox", { name: `${HIDDEN_MODEL} 숨기기` })).toBeChecked();
    // 숨긴 모델도 후보에 남아 풀 수 있다
    await expect(section.getByRole("checkbox", { name: `${KEPT_MODEL} 숨기기` })).not.toBeChecked();
  } finally {
    await clearHidden(page);
  }
});

test("후보를 읽지 못해도 관리자가 저장된 숨김을 풀 수 있다", async ({ page }) => {
  await clearHidden(page);
  const saved = await page.request.put("/api/admin/model-hidden", {
    data: { entries: [{ provider: "openai-codex", model: HIDDEN_MODEL }] },
  });
  expect(saved.ok()).toBeTruthy();
  try {
    await page.route(
      (url) => url.pathname === "/api/admin/model-hidden/candidates",
      (route) => route.fulfill({ json: { catalog: null } }),
    );
    await page.goto("/admin/models");
    const section = page.getByRole("region", { name: "모델 숨김" });
    await expect(section.getByTestId("model-hidden-catalog-missing")).toBeVisible();
    await section
      .getByRole("checkbox", { name: `ChatGPT 구독 ${HIDDEN_MODEL} 숨기기` })
      // 체크를 풀면 그 줄이 사라진다. uncheck 는 사라진 요소의 상태를 확인하려다 멈추므로 누르기만 한다.
      .click();
    await section.getByRole("button", { name: "숨김 저장" }).click();
    await expect(section.getByRole("status")).toHaveText("숨김 설정을 저장했어요.");
    const hidden = await page.request.get("/api/admin/model-hidden");
    expect(((await hidden.json()) as { entries: unknown[] }).entries).toEqual([]);
  } finally {
    await clearHidden(page);
  }
});

type Candidates = { catalog: { providers: { provider: string; name: string; models: string[]; reasoning: object }[]; reasoningEfforts: string[] } };

/**
 * 다시 읽기를 `REFRESHED` 로 답하게 하고, 그 뒤의 숨길 후보 응답을 `after` 가 정한다. 처음 화면은 실제 후보를 읽는다.
 */
async function stubRefreshedCandidates(page: Page, after: (first: Candidates) => Candidates | null): Promise<void> {
  let refreshed = false;
  await page.route(
    (url) => url.pathname === "/api/admin/model-catalog/refresh",
    (route) => {
      refreshed = true;
      return route.fulfill({ json: { status: "REFRESHED", refreshedAt: "2026-01-01T00:00:00Z" } });
    },
  );
  await page.route(
    (url) => url.pathname === "/api/admin/model-hidden/candidates",
    async (route) => {
      if (!refreshed) return route.fallback();
      const response = await route.fetch();
      const next = after((await response.json()) as Candidates);
      return route.fulfill({ response, json: next ?? { catalog: null } });
    },
  );
}

test("목록을 다시 읽으면 숨길 후보에 새 모델이 생기고 저장 전에 고친 체크가 남는다", async ({ page }) => {
  await clearHidden(page);
  await stubRefreshedCandidates(page, (first) => ({
    catalog: {
      ...first.catalog,
      providers: first.catalog.providers.map((row) =>
        row.provider === "openai-codex" ? { ...row, models: [...row.models, "brand-new"] } : row,
      ),
    },
  }));
  await page.goto("/admin/models");
  const section = page.getByRole("region", { name: "모델 숨김" });
  const kept = section.getByRole("checkbox", { name: `${KEPT_MODEL} 숨기기` });
  await kept.check();
  await expect(section.getByRole("checkbox", { name: "brand-new 숨기기" })).toHaveCount(0);

  await page.getByTestId("model-catalog-refresh").click();

  await expect(section.getByRole("checkbox", { name: "brand-new 숨기기" })).toHaveCount(1);
  await expect(kept).toBeChecked();
});

test("다시 읽은 뒤 후보를 불러오지 못하면 보이던 후보를 그대로 두고 알린다", async ({ page }) => {
  await clearHidden(page);
  await stubRefreshedCandidates(page, () => null);
  await page.goto("/admin/models");
  const section = page.getByRole("region", { name: "모델 숨김" });
  const kept = section.getByRole("checkbox", { name: `${KEPT_MODEL} 숨기기` });
  await expect(kept).toHaveCount(1);

  await page.getByTestId("model-catalog-refresh").click();

  await expect(page.getByTestId("model-catalog-refresh-notice")).toContainText(
    "다시 읽은 목록을 불러오지 못했어요. 보이던 목록을 그대로 둬요.",
  );
  await expect(kept).toHaveCount(1);
  await expect(section.getByTestId("model-hidden-catalog-missing")).toHaveCount(0);
});

test("후보에 없는 숨김 항목은 따로 보이고 저장해도 지워지지 않는다", async ({ page }) => {
  await page.route(
    (url) => url.pathname === "/api/admin/model-hidden",
    (route) => {
      if (route.request().method() !== "GET") return route.fallback();
      return route.fulfill({ json: { entries: [{ provider: "openai-codex", model: "retired-model" }] } });
    },
  );
  await page.goto("/admin/models");
  const section = page.getByRole("region", { name: "모델 숨김" });
  await expect(section.getByRole("group", { name: "후보에 없는 숨김" })).toBeVisible();
  await expect(section.getByRole("checkbox", { name: "ChatGPT 구독 retired-model 숨기기" })).toBeChecked();
});

test("관리자가 아닌 사용자는 숨김과 숨길 후보를 읽거나 바꾸지 못한다", async ({ context, page }) => {
  await setSession(context, { email: "member@example.com", name: "가족 사용자" });
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();

  expect((await page.request.get("/api/admin/model-hidden")).status()).toBe(403);
  expect((await page.request.get("/api/admin/model-hidden/candidates")).status()).toBe(403);
  const changed = await page.request.put("/api/admin/model-hidden", { data: { entries: [] } });
  expect(changed.status()).toBe(403);
});
