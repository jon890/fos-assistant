import { connectDemoConnector, disconnectDemoConnector, expect, setSession, test } from "./fixtures.ts";

const MEMBER_EMAIL = "member@example.com";

// 등록이 실패했을 때 해제 오류가 원래 실패를 가리지 않게, 연결을 만든 검사만 해제한다.
let connected = false;

test.afterEach(async () => {
  if (!connected) return;
  connected = false;
  await disconnectDemoConnector(MEMBER_EMAIL);
});

test("MEMBER 주인의 커넥터 에이전트 화면은 도구와 스킬 편집 대신 연결 안내를 보인다", async ({ context, page }) => {
  const agentCode = await connectDemoConnector(MEMBER_EMAIL);
  connected = true;
  await setSession(context, { email: MEMBER_EMAIL, name: "가족 사용자" });

  await page.goto(`/agents/${agentCode}`);

  await expect(page.getByText("연결 화면에서 이 에이전트의 연결 상태를 관리해요.")).toBeVisible();
  await expect(page.getByRole("heading", { name: /도구/ })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: /스킬/ })).toHaveCount(0);
});
