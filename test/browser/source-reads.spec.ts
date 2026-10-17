import { expect, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

type SourceReads = {
  completedCount: number;
  urls: string[];
  requestedUrls?: string[];
  unresolvedCount: number;
  observationComplete: boolean;
};

const EVIL_READS: SourceReads = {
  completedCount: 99,
  urls: ["https://evil.example"],
  requestedUrls: ["https://evil.example/requested"],
  unresolvedCount: 0,
  observationComplete: true,
};

/**
 * 저장된 답에 열람 요약을 끼워 대화를 연다.
 *
 * <p>`activity` 는 도구 호출이 있었던 답에만 붙는다. 원문 주소는 그 작업 과정 블록 안에서만 보인다.
 */
async function openWithSourceReads(
  page: Page,
  sourceReads: SourceReads,
  options: { activity?: boolean } = {},
) {
  const withActivity = options.activity ?? true;
  const created = await page.request.post("/api/chat", {
    data: { text: "원문 열람 검사", agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = (await created.json()) as {
    conversationId: string;
  };
  await page.route(
    `**/api/chat/conversations/${conversationId}/messages`,
    async (route) => {
      const response = await route.fetch();
      const turns = (await response.json()) as Array<Record<string, unknown>>;
      turns.push({
        id: 999_999,
        role: "SYSTEM",
        content: "알림 줄",
        senderName: null,
      });
      for (const turn of turns) {
        if (turn.role !== "ASSISTANT") {
          turn.sourceReads = EVIL_READS;
          continue;
        }
        turn.sourceReads = sourceReads;
        turn.activity = withActivity
          ? { toolCount: 1, subagentCount: 0, durationMs: null }
          : null;
      }
      await route.fulfill({ response, json: turns });
    },
  );
  await page.goto(`/chat/${conversationId}`);
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
}

test("원문 주소는 답 아래에 보이지 않고 작업 과정을 펼치면 새 탭 링크로 보인다", async ({
  page,
}) => {
  await openWithSourceReads(page, {
    completedCount: 2,
    urls: ["https://example.com/path"],
    requestedUrls: [
      "https://example.com/requested",
      "https://example.com/path",
    ],
    unresolvedCount: 1,
    observationComplete: false,
  });

  const block = page.getByTestId("activity-block");
  await expect(block).toBeVisible();
  const reads = page.getByTestId("activity-source-reads");
  await expect(reads).not.toBeVisible();
  await expect(page.getByTestId("source-reads")).toHaveCount(0);
  await expect(block.getByTestId("activity-toggle")).not.toContainText("연 원문");

  await block.getByTestId("activity-toggle").click();

  await expect(reads).toBeVisible();
  // 같은 주소는 한 번만 보이고 집계 문장과 안내 문장은 없다
  await expect(reads.getByRole("link")).toHaveCount(2);
  const link = reads.getByRole("link", { name: "https://example.com/path" });
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noreferrer noopener");
  const requestedLink = reads.getByRole("link", {
    name: "https://example.com/requested",
  });
  await expect(requestedLink).toHaveAttribute("target", "_blank");
  await expect(requestedLink).toHaveAttribute("rel", "noreferrer noopener");
  await expect(reads).not.toContainText("열람 도구 완료");
  await expect(reads).not.toContainText("페이지별 성공");
  await expect(page.getByTestId("user-message")).not.toContainText("연 원문");
  await expect(page.getByTestId("system-message")).not.toContainText("연 원문");
  await expect(page.getByTestId("activity-source-reads")).toHaveCount(1);
});

test("확인한 주소가 없으면 작업 과정에 원문 절을 그리지 않는다", async ({
  page,
}) => {
  await openWithSourceReads(page, {
    completedCount: 2,
    urls: [],
    unresolvedCount: 0,
    observationComplete: true,
  });

  await page.getByTestId("activity-toggle").click();
  await expect(page.getByTestId("activity-source-reads")).toHaveCount(0);
  await expect(page.getByTestId("source-reads")).toHaveCount(0);
});

test("도구 호출이 없는 답에는 주소가 있어도 아무것도 그리지 않는다", async ({
  page,
}) => {
  await openWithSourceReads(
    page,
    {
      completedCount: 1,
      urls: ["https://example.com/path"],
      unresolvedCount: 0,
      observationComplete: true,
    },
    { activity: false },
  );

  await expect(page.getByTestId("activity-block")).toHaveCount(0);
  await expect(page.getByTestId("activity-source-reads")).toHaveCount(0);
  await expect(page.getByTestId("source-reads")).toHaveCount(0);

  await page.reload();
  await expect(page.getByTestId("assistant-message")).toHaveCount(1);
  await expect(page.getByTestId("activity-source-reads")).toHaveCount(0);
});

test("요청 주소만 있어도 작업 과정을 펼치면 보인다", async ({ page }) => {
  await openWithSourceReads(page, {
    completedCount: 1,
    urls: [],
    requestedUrls: ["https://example.com/requested"],
    unresolvedCount: 1,
    observationComplete: true,
  });

  await page.getByTestId("activity-toggle").click();
  await expect(
    page
      .getByTestId("activity-source-reads")
      .getByRole("link", { name: "https://example.com/requested" }),
  ).toBeVisible();
});
