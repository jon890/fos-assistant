import { STEER_PROBE } from "../../../test/e2e/fake-hermes.ts";
import { CONVERSATION_URL, expect, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

function composer(page: Page) {
  return page.getByTestId("composer-shell");
}

function messageBox(page: Page) {
  return page.getByRole("textbox", { name: "메시지" });
}

/** 대화 줄의 사용자 말풍선과 답을 화면 순서대로 「역할:글」 로 읽는다. */
async function rowOrder(page: Page): Promise<string[]> {
  return page
    .locator("[data-testid='user-message'], [data-testid='assistant-message']")
    .evaluateAll((rows) =>
      // 사용자 말풍선은 보낸 시각을 함께 담으므로 글 단락만 읽는다.
      rows.map(
        (row) =>
          `${row.getAttribute("data-testid")}:${(row.querySelector("p")?.textContent ?? "").trim()}`,
      ),
    );
}

test("답이 도는 중 입력창 글은 그 답 바로 위 「전달됨」 말풍선이 되고 그 답은 다시 생성하지 않는다", async ({
  page,
  hermes,
}) => {
  await hermes.holdNextRun();
  await page.goto("/new");
  await messageBox(page).fill(STEER_PROBE);
  await composer(page).getByRole("button", { name: "보내기" }).click();
  await hermes.waitForHeldRun();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(composer(page).getByRole("button", { name: "중지" })).toBeEnabled();

  await messageBox(page).fill("창가로");
  await composer(page).getByRole("button", { name: "보내기" }).click();
  const bubble = page
    .getByRole("listitem")
    .filter({ has: page.getByTestId("user-message").getByText("창가로") });
  await expect(bubble.getByTestId("steer-state")).toHaveText("전달됨");
  await expect(bubble.getByTestId("steer-child-notice")).toHaveCount(0);
  // 받은 글은 대기 칸에 남지 않는다. 대기 칸과 말풍선이 다른 자리라 헷갈리지 않는다.
  await expect(page.getByTestId("pending-item")).toHaveCount(0);
  await expect(messageBox(page)).toHaveValue("");

  await hermes.releaseHeldRun();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1, {
    timeout: 30_000,
  });
  await expect(composer(page).getByRole("button", { name: "중지" })).toHaveCount(
    0,
    { timeout: 30_000 },
  );
  // 이력을 다시 읽은 뒤에도 질문, 말풍선, 답 순서이고 상태가 남는다.
  expect(await rowOrder(page)).toEqual([
    `user-message:${STEER_PROBE}`,
    "user-message:창가로",
    expect.stringMatching(/^assistant-message:/),
  ]);
  await expect(bubble.getByTestId("steer-state")).toHaveText("전달됨");
  const answer = page.getByTestId("assistant-message").last();
  await expect(answer.getByRole("button", { name: "답 복사" })).toBeVisible();
  await expect(
    answer.getByRole("button", { name: "답 다시 만들기", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByTestId("no-answer")).toHaveCount(0);

  await page.reload();
  await expect(bubble.getByTestId("steer-state")).toHaveText("전달됨");
  expect(await rowOrder(page)).toHaveLength(3);
});
