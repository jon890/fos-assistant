import {
  createLegacyConnectorAgent,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";

/**
 * 예전 방식의 연결 에이전트 상세를 본다. 새 연결은 이런 에이전트를 만들지 않으므로 검사에서만 뜨는 경로로 만든다.
 *
 * <p>mobile 과 desktop 이 같은 Control Plane 을 함께 쓰므로 project 마다 다른 사용자로 로그인한다.
 */

function ownerOf(projectName: string) {
  return {
    email: `legacy-connector-${projectName}@example.com`,
    name: "가족 사용자",
  };
}

let legacyCode: string | null = null;

test.afterEach(async ({ context, page }, testInfo) => {
  const owner = ownerOf(testInfo.project.name);
  if (legacyCode !== null) {
    await setSession(context, owner);
    // 검사가 지우지 못하고 끝났으면 여기서 지운다. 이미 지웠으면 404 다.
    const deleted = await page.request.delete(`/api/agents/${legacyCode}`);
    expect([204, 404]).toContain(deleted.status());
    legacyCode = null;
  }
  await disconnectDemoConnector(owner.email);
});

test("예전 방식의 연결 에이전트는 옮겨 가라는 안내와 지우기만 보이고 지울 수 있다", async ({
  context,
  page,
}, testInfo) => {
  const owner = ownerOf(testInfo.project.name);
  await setSession(context, owner);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  legacyCode = await createLegacyConnectorAgent(owner.email);

  await page.goto(`/agents/${legacyCode}`);

  await expect(
    page.getByText(
      "예전 방식의 연결 에이전트예요. 쓰던 에이전트에 이 연결을 붙인 뒤 이 에이전트를 지워 주세요.",
    ),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: /도구/ })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: /스킬/ })).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "이 에이전트가 쓰는 연결" }),
  ).toHaveCount(0);

  const access = page.getByRole("region", { name: "공개와 삭제" });
  await expect(
    access.getByRole("button", { name: "그룹 공개로 변경" }),
  ).toHaveCount(0);
  await access.getByRole("button", { name: "에이전트 지우기" }).click();
  await page
    .getByRole("alertdialog")
    .getByRole("button", { name: "지우기" })
    .click();

  await expect(page).toHaveURL(/\/agents$/);
  const listed = await page.request.get("/api/agents");
  expect(
    ((await listed.json()) as { code: string }[]).map((agent) => agent.code),
  ).not.toContain(legacyCode);
});
