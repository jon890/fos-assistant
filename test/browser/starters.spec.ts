import {
  expect,
  setAgentVisibility,
  setSession,
  test,
  PERSONA_AGENT_CODE,
  PERSONA_FAMILY_AGENT_CODE,
} from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";

test("소개와 추천 질문을 저장하고 새로고침해도 남는다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);

  await page.getByRole("textbox", { name: "성격 비서 소개" }).fill("차분하게 답하는 비서입니다");
  await page.getByRole("textbox", { name: "추천 질문 1" }).fill("오늘 날씨 알려줘");
  await page.getByRole("textbox", { name: "추천 질문 2" }).fill("이번 주 할 일 정리해줘");
  await page.getByRole("textbox", { name: "추천 질문 3" }).fill("메모 남겨줘");
  await page.getByRole("textbox", { name: "추천 질문 4" }).fill("사진 정리해줘");
  await page.getByRole("button", { name: "소개와 추천 질문 저장" }).click();

  await expect(page.getByText("소개와 추천 질문이 저장되었습니다.")).toBeVisible();

  await page.reload();
  await expect(page.getByRole("textbox", { name: "성격 비서 소개" })).toHaveValue(
    "차분하게 답하는 비서입니다",
  );
  await expect(page.getByRole("textbox", { name: "추천 질문 1" })).toHaveValue("오늘 날씨 알려줘");
  await expect(page.getByRole("textbox", { name: "추천 질문 2" })).toHaveValue(
    "이번 주 할 일 정리해줘",
  );
  await expect(page.getByRole("textbox", { name: "추천 질문 3" })).toHaveValue("메모 남겨줘");
  await expect(page.getByRole("textbox", { name: "추천 질문 4" })).toHaveValue("사진 정리해줘");
});

test("가운데 칸을 비우고 저장하면 앞으로 당겨져 채워진다", async ({ page }) => {
  await page.goto(`/agents/${PERSONA_AGENT_CODE}`);

  // 앞 검사가 남긴 값 위에서도 통과하도록 소개와 추천 질문 넷을 모두 명시적으로 채우거나 비운다.
  await page.getByRole("textbox", { name: "성격 비서 소개" }).fill("차분하게 답하는 비서입니다");
  await page.getByRole("textbox", { name: "추천 질문 1" }).fill("오늘 일정 알려줘");
  await page.getByRole("textbox", { name: "추천 질문 2" }).fill("");
  await page.getByRole("textbox", { name: "추천 질문 3" }).fill("맛집 추천해줘");
  await page.getByRole("textbox", { name: "추천 질문 4" }).fill("사진 정리해줘");
  await page.getByRole("button", { name: "소개와 추천 질문 저장" }).click();

  await expect(page.getByText("소개와 추천 질문이 저장되었습니다.")).toBeVisible();
  // 가운데 칸(추천 질문 2)이 빈 채로 저장되면 뒤의 값이 앞으로 당겨진다.
  await expect(page.getByRole("textbox", { name: "추천 질문 1" })).toHaveValue("오늘 일정 알려줘");
  await expect(page.getByRole("textbox", { name: "추천 질문 2" })).toHaveValue("맛집 추천해줘");
  await expect(page.getByRole("textbox", { name: "추천 질문 3" })).toHaveValue("사진 정리해줘");
  await expect(page.getByRole("textbox", { name: "추천 질문 4" })).toHaveValue("");
});

test("가족에게 공개된 에이전트는 소개와 추천 질문이 읽기 전용이다", async ({ context, page }) => {
  // 이 검사 동안만 가족 공개로 두고 끝나면 되돌린다. identity.spec.ts 가 가족 공개 에이전트가
  // 정확히 하나라고 가정하고 있어, 여기서 하나를 더 남겨 두면 그 검사가 어긋난다.
  await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "FAMILY", null);
  try {
    await setSession(context, { email: "member@example.com", name: "가족 구성원" });
    await page.goto(`/agents/${PERSONA_FAMILY_AGENT_CODE}`);

    const tagline = page.getByRole("textbox", { name: "가족 성격 비서 소개" });
    await expect(tagline).toBeVisible();
    await expect(tagline).toHaveAttribute("readonly", "");
    await expect(page.getByRole("textbox", { name: "추천 질문 1" })).toHaveAttribute("readonly", "");
    await expect(page.getByRole("button", { name: "소개와 추천 질문 저장" })).toHaveCount(0);
  } finally {
    await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "PRIVATE", TEST_EMAIL);
  }
});
