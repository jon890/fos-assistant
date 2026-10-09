import { expect, setSession, test } from "./fixtures.ts";
import { clickAndWaitForResponse } from "./helpers.ts";
import { TEST_EMAIL } from "./settings.ts";
import type { ToolsetRequest } from "../../web/src/lib/toolset-request.ts";
import type { CatalogToolset } from "../../web/src/lib/toolset-catalog.ts";

let code: string;
let hidden: string[];

test.beforeEach(async ({ context, page, isolatedMember }) => {
  code = "";
  const catalog = await (await page.request.get("/api/admin/toolsets")).json() as CatalogToolset[];
  hidden = catalog.filter((tool) => tool.hidden).map((tool) => tool.name);
  expect((await page.request.put("/api/admin/toolsets", { data: { hidden: [] } })).ok()).toBeTruthy();
  await setSession(context, isolatedMember);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  const created = await page.request.post("/api/agents", { data: { name: "도구 요청 비서" } });
  expect(created.status()).toBe(201);
  code = ((await created.json()) as { code: string }).code;
});

test.afterEach(async ({ context, page, isolatedMember }) => {
  await setSession(context, isolatedMember);
  if (code) expect((await page.request.delete(`/api/agents/${code}`)).status()).toBe(204);
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  expect((await page.request.put("/api/admin/toolsets", { data: { hidden } })).ok()).toBeTruthy();
});

function row(page: import("../../web/node_modules/@playwright/test/index.js").Page) {
  return page.getByRole("region", { name: "도구", exact: true }).locator("li").filter({ hasText: "그림 만들기" });
}

test("사용 요청을 새로고침해도 유지하고 관리자 알림에서 승인하면 요청자 알림과 실제 켜짐이 이어진다", async ({ page, context, isolatedMember }) => {
  await page.goto(`/agents/${code}`);
  await expect(row(page).getByText("관리자가 확인하면 켜져요")).toBeVisible();
  const sent = await clickAndWaitForResponse(page, row(page).getByRole("button", { name: "사용 요청" }), "POST", /\/tool-requests$/);
  const request = await sent.json() as ToolsetRequest;
  await expect(row(page).getByText("요청 중", { exact: true })).toBeVisible();
  await page.reload();
  await expect(row(page).getByRole("button", { name: "요청 취소" })).toBeEnabled();

  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await page.goto("/notifications");
  const notification = page.getByRole("button", { name: /도구 사용 요청이 있어요/ }).first();
  await clickAndWaitForResponse(page, notification, "POST", /\/api\/notifications\/[^/]+\/read$/);
  await expect(page).toHaveURL(new RegExp(`/admin/agents/${code}$`));
  const decision = page.getByRole("region", { name: "도구 사용 요청", exact: true });
  await expect(decision.getByText(/그림 만들기/)).toBeVisible();
  await clickAndWaitForResponse(page, decision.getByRole("button", { name: "승인", exact: true }), "POST", /\/api\/admin\/tool-requests\//);
  await expect(decision.getByRole("status")).toHaveText("승인됐어요");
  await expect(row(page).getByRole("switch")).toHaveAttribute("aria-checked", "true");
  await page.reload();
  await expect(row(page).getByRole("switch")).toHaveAttribute("aria-checked", "true");

  await setSession(context, isolatedMember);
  await page.goto("/notifications");
  await clickAndWaitForResponse(page, page.getByRole("button", { name: /도구 사용 요청이 승인됐어요/ }), "POST", /\/api\/notifications\/[^/]+\/read$/);
  await expect(page).toHaveURL(new RegExp(`/tool-requests/${request.id}$`));
  await expect(page.getByRole("region", { name: "도구 사용 요청", exact: true }).getByRole("status")).toHaveText("승인됐어요");
  await page.getByRole("link", { name: "에이전트 보기" }).click();
  await expect(row(page).getByRole("switch")).toHaveAttribute("aria-checked", "true");
  await expect(row(page).getByText("승인됐어요", { exact: true })).toBeVisible();
});

test("거절에는 사유를 쓰고 요청자는 알림과 에이전트에서 사유를 읽은 뒤 다시 요청할 수 있다", async ({ page, context, isolatedMember }) => {
  const sent = await page.request.post(`/api/agents/${code}/tool-requests`, { data: { toolset: "image_gen" } });
  expect(sent.ok()).toBeTruthy();
  const request = await sent.json() as ToolsetRequest;
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await page.goto(`/admin/agents/${code}`);
  const decision = page.getByRole("region", { name: "도구 사용 요청", exact: true });
  await expect(decision.getByRole("button", { name: "거절", exact: true })).toBeDisabled();
  await decision.getByLabel("거절 사유 한 줄").fill("다음 달에 다시 확인할게요.");
  await clickAndWaitForResponse(page, decision.getByRole("button", { name: "거절", exact: true }), "POST", /\/api\/admin\/tool-requests\//);
  await expect(decision.getByRole("status")).toHaveText("거절됐어요");
  await setSession(context, isolatedMember);
  await page.goto("/notifications");
  await clickAndWaitForResponse(page, page.getByRole("button", { name: /도구 사용 요청 결과가 있어요/ }), "POST", /\/api\/notifications\/[^/]+\/read$/);
  await expect(page).toHaveURL(new RegExp(`/tool-requests/${request.id}$`));
  await expect(page.getByText("다음 달에 다시 확인할게요.", { exact: true })).toBeVisible();
  await page.goto(`/agents/${code}`);
  await expect(row(page).getByRole("status")).toHaveText("거절됐어요 · 다음 달에 다시 확인할게요.");
  await expect(row(page).getByRole("switch")).toHaveAttribute("aria-checked", "false");
  await clickAndWaitForResponse(page, row(page).getByRole("button", { name: "사용 요청" }), "POST", /\/tool-requests$/);
  await expect(row(page).getByRole("status")).toHaveText("요청 중");
});

test("대기 요청을 취소하고 다시 요청할 수 있으며 일반 사용자는 관리자 승인 경로를 쓸 수 없다", async ({ page }) => {
  await page.goto(`/agents/${code}`);
  const sent = await clickAndWaitForResponse(page, row(page).getByRole("button", { name: "사용 요청" }), "POST", /\/tool-requests$/);
  const request = await sent.json() as ToolsetRequest;
  const duplicate = await page.request.post(`/api/agents/${code}/tool-requests`, { data: { toolset: "image_gen" } });
  expect((await duplicate.json() as ToolsetRequest).id).toBe(request.id);
  expect((await page.request.post(`/api/admin/tool-requests/${request.id}`, { data: { approve: true } })).status()).toBe(403);
  await clickAndWaitForResponse(page, row(page).getByRole("button", { name: "요청 취소" }), "POST", /\/cancel$/);
  await expect(row(page).getByRole("status")).toHaveText("취소했어요");
  await page.reload();
  await expect(row(page).getByRole("status")).toHaveText("취소했어요");
  const again = await clickAndWaitForResponse(page, row(page).getByRole("button", { name: "사용 요청" }), "POST", /\/tool-requests$/);
  expect((await again.json() as ToolsetRequest).id).not.toBe(request.id);
});

test("요청 뒤 숨긴 도구는 승인 때 만료하고 새 요청 대상에서도 빠진다", async ({ page, context, isolatedMember }) => {
  const response = await page.request.post(`/api/agents/${code}/tool-requests`, { data: { toolset: "image_gen" } });
  expect(response.ok()).toBeTruthy();
  const request = await response.json() as ToolsetRequest;
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  expect((await page.request.put("/api/admin/toolsets", { data: { hidden: ["image_gen"] } })).ok()).toBeTruthy();
  await page.goto(`/admin/agents/${code}`);
  const decision = page.getByRole("region", { name: "도구 사용 요청", exact: true });
  await clickAndWaitForResponse(page, decision.getByRole("button", { name: "승인", exact: true }), "POST", /\/api\/admin\/tool-requests\//);
  await expect(decision.getByRole("status")).toHaveText("요청이 만료됐어요");
  await expect(row(page).getByRole("switch")).toHaveAttribute("aria-checked", "false");
  await setSession(context, isolatedMember);
  await page.goto(`/tool-requests/${request.id}`);
  await expect(page.getByRole("region", { name: "도구 사용 요청", exact: true }).getByRole("status")).toHaveText("요청이 만료됐어요");
  await page.goto(`/agents/${code}`);
  await expect(row(page)).toHaveCount(0);
});

test("요청한 에이전트를 지운 뒤에도 관리자는 요청을 확인하고 권한을 주지 않은 채 만료한다", async ({ page, context }) => {
  const response = await page.request.post(`/api/agents/${code}/tool-requests`, { data: { toolset: "image_gen" } });
  expect(response.ok()).toBeTruthy();
  const request = await response.json() as ToolsetRequest;
  expect((await page.request.delete(`/api/agents/${code}`)).status()).toBe(204);
  code = "";
  await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
  await page.goto(`/admin/tool-requests/${request.id}`);
  const decision = page.getByRole("region", { name: "도구 사용 요청", exact: true });
  await expect(decision.getByText("지운 에이전트의 요청이에요.")).toBeVisible();
  await clickAndWaitForResponse(page, decision.getByRole("button", { name: "승인", exact: true }), "POST", /\/api\/admin\/tool-requests\//);
  await expect(decision.getByRole("status")).toHaveText("요청이 만료됐어요");
});
