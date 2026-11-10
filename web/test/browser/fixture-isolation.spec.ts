import { expect, setSession, test } from "./fixtures.ts";
import { isolatedUser } from "./helpers.ts";

test("대역의 장애와 쓰지 않은 보류를 다음 검사에 남기지 않는다", async ({
  hermes,
}) => {
  await hermes.busy();
  await hermes.holdNextRun();
  await hermes.holdNextSoul();
  await hermes.setReadinessOutage("unavailable");
  await hermes.blockProvider("openai-codex");
  // 의도적으로 직접 복구하지 않는다. 공통 fixture의 정리로 다음 검사가 평소 상태에서 시작해야 한다.
});

test("검사 전용 사용자는 다른 폭과 반복의 대화가 없는 상태로 시작한다", async ({
  context,
  page,
}, testInfo) => {
  await setSession(context, isolatedUser(testInfo, "isolation"));
  const listed = await page.request.get("/api/chat/conversations");
  expect(listed.ok()).toBeTruthy();
  expect(await listed.json()).toEqual({ items: [], nextCursor: null });
  const created = await page.request.post("/api/agents", {
    data: { name: "검사 비서" },
  });
  expect(created.status()).toBe(201);
  const { code } = await created.json();
  try {
    const sent = await page.request.post("/api/chat", {
      data: { text: "격리된 대화 검사", agentCode: code },
    });
    expect(sent.ok()).toBeTruthy();
    await page.goto(`/agents/${code}`);
    await expect(
      page.getByRole("textbox", { name: "검사 비서 성격" }),
    ).toBeVisible();
  } finally {
    expect((await page.request.delete(`/api/agents/${code}`)).status()).toBe(
      204,
    );
  }
});
