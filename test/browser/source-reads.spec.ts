import { expect, test } from "./fixtures.ts";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";

type SourceReads = {
  completedCount: number;
  urls: string[];
  requestedUrls?: string[];
  unresolvedCount: number;
  observationComplete: boolean;
};

async function openWithSourceReads(page: Page, sourceReads: SourceReads) {
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
        id: "temporary-source-read",
        role: "ASSISTANT",
        content: "임시 답",
        senderName: null,
        sourceReads: {
          completedCount: 99,
          urls: ["https://evil.example"],
          requestedUrls: ["https://evil.example/requested"],
          unresolvedCount: 0,
          observationComplete: true,
        },
      });
      turns.push({
        id: 999_999,
        role: "SYSTEM",
        content: "알림 줄",
        senderName: null,
      });
      for (const turn of turns) {
        if (turn.role !== "ASSISTANT") {
          turn.sourceReads = {
            completedCount: 99,
            urls: ["https://evil.example"],
            requestedUrls: ["https://evil.example/requested"],
            unresolvedCount: 0,
            observationComplete: true,
          };
        }
        if (turn.role === "ASSISTANT") {
          turn.sourceReads = sourceReads;
        }
      }
      await route.fulfill({ response, json: turns });
    },
  );
  await page.goto(`/chat/${conversationId}`);
  return page.getByTestId("source-reads");
}

test("저장된 답의 원문 목록은 완료 수와 주소 수를 구분하고 새 탭으로 연다", async ({
  page,
}) => {
  const reads = await openWithSourceReads(page, {
    completedCount: 2,
    urls: ["https://example.com/path"],
    requestedUrls: ["https://example.com/requested"],
    unresolvedCount: 1,
    observationComplete: false,
  });

  await expect(reads).toContainText("열람 도구 완료 2회 · 확인한 주소 1개");
  await expect(
    reads.getByRole("heading", { name: "원문 결과에서 확인한 주소" }),
  ).toBeVisible();
  await expect(
    reads.getByRole("heading", { name: "열람 요청 · 도구 호출 성공" }),
  ).toBeVisible();
  const link = reads.getByRole("link", { name: "https://example.com/path" });
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noreferrer noopener");
  const requestedLink = reads.getByRole("link", {
    name: "https://example.com/requested",
  });
  await expect(requestedLink).toHaveAttribute("target", "_blank");
  await expect(requestedLink).toHaveAttribute("rel", "noreferrer noopener");
  await expect(reads).toContainText("페이지별 성공은 확인하지 못했어요.");
  await expect(reads).toContainText(
    "일부 열람 결과에서 주소를 확인하지 못했어요.",
  );
  await expect(reads).toContainText("열람 기록을 모두 확인하지 못했어요.");
  await expect(page.getByTestId("user-message")).not.toContainText(
    "이번에 연 원문",
  );
  await expect(page.getByTestId("system-message")).not.toContainText(
    "이번에 연 원문",
  );
  await expect(
    page.getByTestId("assistant-message").filter({ hasText: "임시 답" }),
  ).not.toContainText("이번에 연 원문");
  await expect(page.getByTestId("source-reads")).toHaveCount(1);

  await page.reload();
  await expect(page.getByTestId("source-reads")).toContainText(
    "확인한 주소 1개",
  );
});

test("확인한 주소가 없으면 빈 상태를 표시한다", async ({ page }) => {
  const reads = await openWithSourceReads(page, {
    completedCount: 2,
    urls: [],
    unresolvedCount: 0,
    observationComplete: true,
  });

  await expect(reads).toContainText("열람 도구 완료 2회 · 확인한 주소 0개");
  await expect(reads).toContainText("기록에서 확인한 원문 주소가 없어요.");
});

test("요청 주소만 있으면 빈 상태 없이 호출 성공 근거를 보인다", async ({
  page,
}) => {
  const reads = await openWithSourceReads(page, {
    completedCount: 1,
    urls: [],
    requestedUrls: ["https://example.com/requested"],
    unresolvedCount: 1,
    observationComplete: true,
  });

  await expect(reads).toContainText("열람 도구 완료 1회 · 확인한 주소 0개");
  await expect(
    reads.getByRole("heading", { name: "열람 요청 · 도구 호출 성공" }),
  ).toBeVisible();
  await expect(reads).toContainText("페이지별 성공은 확인하지 못했어요.");
  await expect(reads).not.toContainText("기록에서 확인한 원문 주소가 없어요.");
});
