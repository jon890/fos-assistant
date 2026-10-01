import { ASK_CARD_PROBE } from "../e2e/fake-hermes.ts";
import { expect, test } from "./fixtures.ts";

test("답 끝의 질문을 카드로 그리고 고른 답을 다음 메시지로 보낸다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill(ASK_CARD_PROBE);
  await page.getByRole("button", { name: "보내기", exact: true }).click();

  const card = page.getByTestId("ask-card");
  await expect(card).toBeVisible();
  await expect(page.getByTestId("assistant-message").last()).toContainText("두 가지만 알려 줘");
  // 태그가 글자로 새지 않는다.
  await expect(page.getByTestId("assistant-message").last()).not.toContainText("<question");

  const submit = card.getByTestId("ask-submit");
  await expect(submit).toBeDisabled();
  await card.getByRole("radio", { name: "행복담" }).check();
  await expect(submit).toBeDisabled();
  await card.getByRole("checkbox", { name: "국밥" }).check();
  await card.getByRole("checkbox", { name: "직접 입력" }).check();
  await card.getByRole("textbox", { name: "먹은 메뉴 직접 입력" }).fill("만두");
  await submit.click();

  await expect(page.getByTestId("user-message").last().locator("p")).toHaveText("식당 이름: 행복담\n먹은 메뉴: 국밥, 만두");
  // 답이 온 뒤 지난 카드에도 고른 답이 남고 누를 수 없다.
  await expect(page.getByTestId("assistant-message")).toHaveCount(2);
  await expect(card.getByTestId("ask-submit")).toHaveCount(0);
  await expect(card.getByRole("radio", { name: "행복담" })).toBeDisabled();
  await expect(card.getByRole("radio", { name: "행복담" })).toBeChecked();
  await expect(card.getByRole("checkbox", { name: "국밥" })).toBeChecked();
  await expect(card.getByRole("checkbox", { name: "직접 입력" })).toBeChecked();
  await expect(card).toContainText("만두");

  await page.reload();
  const restored = page.getByTestId("ask-card");
  await expect(restored.getByRole("radio", { name: "행복담" })).toBeChecked();
  await expect(restored.getByRole("checkbox", { name: "국밥" })).toBeChecked();
  await expect(restored.getByRole("checkbox", { name: "직접 입력" })).toBeChecked();
  await expect(restored).toContainText("만두");
});

test("카드 대신 입력창으로 답해도 된다", async ({ page }) => {
  await page.goto("/");
  const composer = page.getByRole("textbox", { name: "메시지" });
  await composer.fill(ASK_CARD_PROBE);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByTestId("ask-card")).toBeVisible();
  // 블록이 닫히면 답이 끝나기 전에도 카드가 보인다. 답이 끝나 중지 단추가 사라진 뒤에 입력한다.
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toHaveCount(0);

  await composer.fill("잘 모르겠어");
  await page.getByRole("button", { name: "보내기", exact: true }).click();

  await expect(page.getByTestId("user-message").last().locator("p")).toHaveText("잘 모르겠어");
  await expect(page.getByTestId("ask-submit")).toHaveCount(0);
  await expect(page.getByTestId("ask-card").getByRole("radio", { name: "행복담" })).not.toBeChecked();
});

test("보낸 답이 전송에 실패하면 카드를 다시 누를 수 있다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill(ASK_CARD_PROBE);
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  const card = page.getByTestId("ask-card");
  await expect(card).toBeVisible();
  // 답이 끝난 뒤에 누른다. 답이 오는 동안 누른 답은 대기 메시지로 쌓여 아래에서 실패시킬 요청을 지나지 않는다.
  await expect(page.getByTestId("composer-shell").getByRole("button", { name: "중지" })).toHaveCount(0);

  await page.route("**/api/chat/stream", (route) => route.fulfill({
    status: 503, contentType: "application/json", body: JSON.stringify({ code: "HERMES_UNAVAILABLE", message: "down" }),
  }), { times: 1 });
  await card.getByRole("radio", { name: "행복담" }).check();
  await card.getByRole("checkbox", { name: "국밥" }).check();
  await card.getByTestId("ask-submit").click();

  // 실패한 답이 치워지면 카드가 다시 마지막 답의 카드가 된다. 고른 것도 그대로 남는다.
  await expect(card.getByTestId("ask-submit")).toBeEnabled();
  await expect(card.getByRole("radio", { name: "행복담" })).toBeChecked();
  await card.getByTestId("ask-submit").click();
  await expect(page.getByTestId("user-message").last().locator("p")).toHaveText("식당 이름: 행복담\n먹은 메뉴: 국밥");
});
