import { expect, setSession, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";
import { TEST_EMAIL } from "./settings.ts";
import type { CatalogToolset } from "../../web/src/lib/toolset-catalog.ts";
import type { AgentToolsView } from "../../web/src/lib/agent.ts";

test("관리자가 활성 도구를 숨겨도 켜짐은 보존하고 관리자 화면에서 끌 수 있다", async ({ page, context, isolatedMember }) => {
  const originalCatalogResponse = await page.request.get("/api/admin/toolsets");
  expect(originalCatalogResponse.ok()).toBeTruthy();
  const originalCatalog = await originalCatalogResponse.json() as CatalogToolset[];
  const originalTools = await (await page.request.get("/api/admin/agents/browser/tools")).json() as AgentToolsView;
  const originalEnabled = originalTools.toolsets.filter((tool) => tool.enabled).map((tool) => tool.name);
  try {
    expect((await page.request.put("/api/admin/toolsets", { data: { hidden: [] } })).ok()).toBeTruthy();
    expect((await page.request.put("/api/admin/agents/browser/tools", { data: { enabled: ["spotify"] } })).ok()).toBeTruthy();
    await page.goto("/admin/tools");
    const section = page.getByRole("region", { name: "화면에 보일 도구" });
    const row = section.locator("li").filter({ has: page.getByRole("switch", { name: "Spotify 보이기", exact: true }) });
    const switchButton = row.getByRole("switch", { name: "Spotify 보이기", exact: true });
    await expect(switchButton).toHaveAttribute("aria-checked", "true");
    await expect(row.getByRole("link", { name: "브라우저 비서", exact: true })).toHaveAttribute("href", "/admin/agents/browser");
    const catalog = await (await page.request.get("/api/admin/toolsets")).json() as CatalogToolset[];
    const count = catalog.find((tool) => tool.name === "spotify")!.enabledAgents.length;
    await expect(row.getByText(`보임 · 켜진 에이전트 ${count}개`)).toBeVisible();
    // 204에는 본문이 없다. 응답 상태와 화면 반영을 함께 확인한다.
    const hiddenResponse = page.waitForResponse((response) =>
      new URL(response.url()).pathname === "/api/admin/toolsets" && response.request().method() === "PUT");
    await switchButton.click();
    expect((await hiddenResponse).status()).toBe(204);
    await expect(switchButton).toHaveAttribute("aria-checked", "false");
    await page.reload();
    await expect(switchButton).toHaveAttribute("aria-checked", "false");

    const general = await (await page.request.get("/api/agents/browser/tools")).json() as AgentToolsView;
    expect(general.toolsets.some((tool) => tool.name === "spotify")).toBe(false);
    expect((await page.request.put("/api/agents/browser/tools", { data: { enabled: ["spotify"] } })).status()).toBe(403);
    expect((await page.request.put("/api/agents/browser/tools", { data: { enabled: ["web"] } })).ok()).toBeTruthy();
    const preserved = await (await page.request.get("/api/admin/agents/browser/tools")).json() as AgentToolsView;
    expect(preserved.toolsets.find((tool) => tool.name === "spotify")).toMatchObject({ enabled: true, hidden: true });
    await page.goto("/agents/browser");
    await expect(page.getByRole("region", { name: "도구" }).getByRole("switch", { name: "Spotify 도구" })).toHaveCount(0);

    await page.goto("/admin/agents/browser");
    const adminRow = page.getByRole("region", { name: "도구" }).locator("li").filter({ hasText: "Spotify" });
    await expect(adminRow.getByText("일반 화면에서 숨김")).toBeVisible();
    await clickAndWaitForResponse(page, adminRow.getByRole("switch", { name: "Spotify 도구" }), "PUT", /\/api\/admin\/agents\/browser\/tools$/);
    await expect(adminRow.getByRole("switch")).toHaveAttribute("aria-checked", "false");
    await page.goto("/admin/tools");
    await expect(row.getByRole("link", { name: "브라우저 비서", exact: true })).toHaveCount(0);

    await setSession(context, isolatedMember);
    expect((await page.request.get("/api/me")).ok()).toBeTruthy();
    expect((await page.request.get("/api/admin/toolsets")).status()).toBe(403);
    expect((await page.request.put("/api/admin/toolsets", { data: { hidden: [] } })).status()).toBe(403);
    await page.goto("/admin/tools");
    await expect(page).toHaveURL(/\/$/);
  } finally {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    expect((await page.request.put("/api/admin/toolsets", { data: { hidden: originalCatalog.filter((tool) => tool.hidden).map((tool) => tool.name) } })).ok()).toBeTruthy();
    expect((await page.request.put("/api/admin/agents/browser/tools", { data: { enabled: originalEnabled } })).ok()).toBeTruthy();
  }
});

test("도구 목록을 읽지 못하면 재시도하고 저장 실패는 기존 보임 상태를 유지한다", async ({ page }) => {
  let unavailable = true;
  await page.route("**/api/admin/toolsets", async (route) => {
    if (unavailable || route.request().method() === "PUT") {
      return route.fulfill({ status: 503, json: { code: "HERMES_UNAVAILABLE", message: "unavailable" } });
    }
    return route.fulfill({ json: [{ name: "spotify", label: "Spotify", description: "음악", hidden: false, enabledAgents: [] }] });
  });
  await page.goto("/admin/tools");
  const section = page.getByRole("region", { name: "화면에 보일 도구" });
  await expect(section.getByRole("alert")).toHaveText("도구 목록을 불러오지 못했어요. 다시 불러와 주세요.");
  unavailable = false;
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  const switchButton = page.getByRole("switch", { name: "Spotify 보이기" });
  await expect(switchButton).toHaveAttribute("aria-checked", "true");
  await switchButton.click();
  await expect(section.getByRole("alert")).toHaveText("도구 숨김을 저장하지 못했어요. 다시 시도해 주세요.");
  await expect(switchButton).toHaveAttribute("aria-checked", "true");
});
