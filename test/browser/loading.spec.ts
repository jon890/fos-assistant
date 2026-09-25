import { expect, PERSONA_AGENT_CODE, test } from "./fixtures.ts";

test("화면을 옮기면 뼈대가 먼저 보이고 이전 화면의 제목은 사라진다", async ({ page, hermes }) => {
  await page.goto("/agents");
  await expect(page.getByRole("heading", { name: "에이전트" })).toBeVisible();

  await hermes.holdNextSoul();
  try {
    await page.locator(`a[href="/agents/${PERSONA_AGENT_CODE}"]`).click();

    await expect(page.getByTestId("page-skeleton")).toBeVisible();
    await expect(page.getByRole("heading", { name: "에이전트" })).toHaveCount(0);

    await hermes.releaseHeldSoul();

    await expect(page.getByTestId("page-skeleton")).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
  } finally {
    await hermes.releaseHeldSoul();
  }
});

test("뼈대의 폭이 내용이 온 뒤 바깥 틀의 폭과 같다", async ({ page, hermes }) => {
  await page.goto("/agents");

  await hermes.holdNextSoul();
  try {
    await page.locator(`a[href="/agents/${PERSONA_AGENT_CODE}"]`).click();

    // 같은 자리(main 바로 아래 첫 칸)가 뼈대에서 내용으로 바뀐다. 로딩 중에는 뼈대의 바깥 틀이고,
    // 내용이 오면 PersonaEditor 의 바깥 틀이다.
    const container = page.locator("main > div").first();
    await expect(container).toHaveAttribute("data-testid", "page-skeleton");
    const skeletonBox = await container.boundingBox();

    await hermes.releaseHeldSoul();

    await expect(page.getByRole("textbox", { name: "성격 비서 성격" })).toBeVisible();
    const editorBox = await container.boundingBox();

    expect(skeletonBox).not.toBeNull();
    expect(editorBox).not.toBeNull();
    expect(Math.abs((skeletonBox?.width ?? 0) - (editorBox?.width ?? 0))).toBeLessThanOrEqual(1);
  } finally {
    await hermes.releaseHeldSoul();
  }
});
