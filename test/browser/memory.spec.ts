import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, setSession, test } from "./fixtures.ts";
import { TEST_EMAIL } from "./settings.ts";
import {
  cleanupMemories,
  confirmDelete,
  createMemory,
  expectNoHorizontalOverflow,
  memoryRow,
  openRow,
  reloadMemoryListBySaving,
} from "./memory-page.ts";

type StubMemory = Record<string, unknown> & {
  id: number;
  title: string;
  status: string;
};

function stub(
  id: number,
  title: string,
  overrides: Record<string, unknown> = {},
): StubMemory {
  return {
    id,
    scope: "USER",
    ownerUserId: 1,
    title,
    content: `${title} 내용`,
    alwaysInject: false,
    status: "ACCEPTED",
    proposedByExecutionId: null,
    sourceAgentName: null,
    sourceAgentDeleted: false,
    sensitive: false,
    omittedFromContext: false,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

/** 화면의 기억 목록 요청을 가짜 목록으로 바꾸고 다시 읽게 한다. 서버가 그린 첫 목록은 바꿀 수 없다. */
async function showStubMemories(page: Page, list: () => unknown[]) {
  await page.route("**/api/memories", async (route) =>
    route.request().method() === "GET"
      ? route.fulfill({ json: list() })
      : route.fallback(),
  );
  const title = `목록 준비 ${Date.now()} ${Math.random()}`;
  const { id } = await createMemory(page, {
    scope: "USER",
    title,
    content: "목록 다시 읽기 검사",
  });
  try {
    await page.goto("/memory");
    await reloadMemoryListBySaving(page, title);
  } finally {
    await page.request.delete(`/api/memories/${id}`);
  }
}

test("손으로 만드는 양식은 보이지 않고 외부 서비스 연결은 접혀 있다", async ({
  page,
}) => {
  await page.goto("/memory");
  await expect(
    page.getByRole("heading", { name: "기억", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "새 기억" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "새 문서" })).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "외부 서비스 연결" }),
  ).toBeHidden();
  await expect(
    page.getByRole("heading", { name: "기존 기록 가져오기" }),
  ).toHaveCount(0);

  await page.locator("summary", { hasText: "외부 서비스 연결" }).click();
  await expect(
    page.getByRole("heading", { name: "외부 서비스 연결" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "기존 기록 가져오기" }),
  ).toHaveCount(0);
});

test("목록에서 기억을 눌러 열고 고치고 지운다", async ({ page }, testInfo) => {
  const title = `음식 선호 ${testInfo.project.name} ${Date.now()}`;
  try {
    await createMemory(page, {
      scope: "USER",
      title,
      content: "국수는 맵지 않게 먹는다",
    });
    await page.goto("/memory");

    const row = memoryRow(page, title);
    await expect(row).toBeVisible();
    await expect(row.getByText("나에 대해")).toBeVisible();
    await expect(row.getByTestId("memory-source")).toHaveText("직접 남김");
    // 펼치기 전에는 본문이 보이지 않는다.
    await expect(
      page.getByText("국수는 맵지 않게 먹는다", { exact: true }),
    ).toHaveCount(0);

    await openRow(page, title);
    await expect(
      row.getByText("국수는 맵지 않게 먹는다", { exact: true }),
    ).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await row.getByRole("button", { name: "고치기" }).click();
    await row.getByLabel("내용").fill("국수는 맵지 않게 먹는다.");
    await row.getByLabel("답을 만들 때 항상 함께 넣기").check();
    await row.getByRole("button", { name: "저장" }).click();
    await expect(
      row.getByText("국수는 맵지 않게 먹는다.", { exact: true }),
    ).toBeVisible();
    await expect(row.getByText("답을 만들 때 항상 함께 넣음")).toBeVisible();

    await row.getByRole("button", { name: "지우기" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("이 기억을 지울까요?")).toBeVisible();
    await dialog.getByRole("button", { name: "취소" }).click();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect(row).toBeVisible();

    await confirmDelete(page, title);
    await expect(memoryRow(page, title)).toHaveCount(0);
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("한 번에 한 줄만 펼친다", async ({ page }) => {
  await showStubMemories(page, () => [
    stub(930, "첫째 기억"),
    stub(931, "둘째 기억"),
  ]);
  await openRow(page, "첫째 기억");
  await expect(page.getByText("첫째 기억 내용")).toBeVisible();
  await openRow(page, "둘째 기억");
  await expect(page.getByText("둘째 기억 내용")).toBeVisible();
  await expect(page.getByText("첫째 기억 내용")).toHaveCount(0);
});

test("MEMBER 역할은 그룹 기억을 열어도 고치기와 지우기를 보지 않는다", async ({
  context,
  page,
  isolatedMember,
}, testInfo) => {
  const title = `MEMBER 편집 금지 ${testInfo.project.name} ${Date.now()}`;
  try {
    await createMemory(page, {
      scope: "GROUP",
      title,
      content: "그룹만 아는 내용",
    });
    await setSession(context, isolatedMember);
    // 화면의 병렬 조회보다 먼저 사용자를 준비하고 이 시험에서 쓸 권한을 확인한다.
    const me = await page.request.get("/api/me");
    expect(me.ok()).toBeTruthy();
    expect((await me.json()).role).toBe("MEMBER");
    await page.goto("/memory");
    const row = memoryRow(page, title);
    await expect(row.getByText("그룹", { exact: true })).toBeVisible();
    await openRow(page, title);
    await expect(row.getByText("그룹만 아는 내용")).toBeVisible();
    await expect(row.getByRole("button", { name: "고치기" })).toHaveCount(0);
    await expect(row.getByRole("button", { name: "지우기" })).toHaveCount(0);
  } finally {
    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await cleanupMemories(page, [title]);
  }
});

test("에이전트가 남긴 기억에 남긴 에이전트를 보이고 따로 모아 본다", async ({
  page,
}) => {
  await showStubMemories(page, () => [
    stub(940, "보이는 에이전트 기억", {
      proposedByExecutionId: 11,
      sourceAgentName: "가족 비서",
    }),
    stub(941, "모르는 에이전트 기억", { proposedByExecutionId: 12 }),
    stub(942, "지운 에이전트 기억", {
      proposedByExecutionId: 13,
      sourceAgentDeleted: true,
    }),
    stub(943, "직접 남긴 기억", { scope: "GROUP" }),
  ]);
  await expect(
    memoryRow(page, "보이는 에이전트 기억").getByTestId("memory-source"),
  ).toHaveText("가족 비서가 남김");
  await expect(
    memoryRow(page, "모르는 에이전트 기억").getByTestId("memory-source"),
  ).toHaveText("에이전트가 남김");
  await expect(
    memoryRow(page, "지운 에이전트 기억").getByTestId("memory-source"),
  ).toHaveText("지운 에이전트가 남김");
  await expect(
    memoryRow(page, "직접 남긴 기억").getByTestId("memory-source"),
  ).toHaveText("직접 남김");

  await page
    .getByRole("button", { name: "에이전트가 남김", exact: true })
    .click();
  await expect(memoryRow(page, "보이는 에이전트 기억")).toBeVisible();
  await expect(memoryRow(page, "직접 남긴 기억")).toHaveCount(0);

  await page.getByRole("button", { name: "그룹", exact: true }).click();
  await expect(memoryRow(page, "직접 남긴 기억")).toBeVisible();
  await expect(memoryRow(page, "보이는 에이전트 기억")).toHaveCount(0);
  await expectNoHorizontalOverflow(page);
});

test("제안을 열어 받아들이면 목록과 머리의 미처리 수가 함께 갱신된다", async ({
  page,
}) => {
  const proposed = stub(910, "검토할 제안", {
    status: "PROPOSED",
    proposedByExecutionId: 21,
    sourceAgentName: "가족 비서",
  });
  let state: unknown[] = [proposed];
  await page.route("**/api/memories/910/accept", async (route) => {
    state = [{ ...proposed, status: "ACCEPTED" }];
    await route.fulfill({ json: state[0] });
  });
  await showStubMemories(page, () => state);
  await expect(
    page.getByRole("heading", { name: "검토할 기억" }),
  ).toBeVisible();
  await expect(page.getByTestId("memory-proposal-count")).toHaveText("1");
  const row = memoryRow(page, "검토할 제안");
  await expect(row.getByTestId("memory-source")).toHaveText("가족 비서가 남김");

  await openRow(page, "검토할 제안");
  await expect(row.getByText("검토할 제안 내용")).toBeVisible();
  await row.getByRole("button", { name: "받아들이기" }).click();
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toHaveCount(
    0,
  );
  await expect(page.getByTestId("memory-proposal-count")).toHaveCount(0);
  await expect(memoryRow(page, "검토할 제안")).toBeVisible();
});

test("제안을 거절하면 제안 절과 머리의 미처리 수가 사라진다", async ({
  page,
}) => {
  const proposed = stub(911, "거절할 제안", {
    status: "PROPOSED",
    proposedByExecutionId: 22,
  });
  let state: unknown[] = [proposed];
  await page.route("**/api/memories/911/reject", async (route) => {
    state = [];
    await route.fulfill({ json: proposed });
  });
  await showStubMemories(page, () => state);
  await expect(page.getByTestId("memory-proposal-count")).toHaveText("1");
  await openRow(page, "거절할 제안");
  await page.getByRole("button", { name: "거절", exact: true }).click();
  await expect(page.getByRole("heading", { name: "검토할 기억" })).toHaveCount(
    0,
  );
  await expect(page.getByTestId("memory-proposal-count")).toHaveCount(0);
});

test("기억을 고치지 못하면 닫지 않고 오류를 보인다", async ({ page }) => {
  await showStubMemories(page, () => [stub(950, "실패할 기억")]);
  await page.route("**/api/memories/950", async (route) =>
    route.fulfill({ status: 500 }),
  );
  await openRow(page, "실패할 기억");
  const row = memoryRow(page, "실패할 기억");
  await row.getByRole("button", { name: "고치기" }).click();
  await row.getByLabel("내용").fill("바뀐 내용");
  await row.getByRole("button", { name: "저장" }).click();
  await expect(row.getByRole("alert")).toHaveText("기억을 고치지 못했어요.");
  await expect(row.getByLabel("내용")).toBeVisible();
});

test("민감한 기억은 본문을 보이지 않고 고치기 없이 지우기만 둔다", async ({
  page,
}) => {
  await showStubMemories(page, () => [
    stub(960, "민감한 기억", { content: "", sensitive: true }),
  ]);
  const row = memoryRow(page, "민감한 기억");
  await expect(row.getByText("민감", { exact: true })).toBeVisible();
  await openRow(page, "민감한 기억");
  await expect(
    row.getByText("민감한 내용이라 여기서는 보이지 않아요."),
  ).toBeVisible();
  await expect(row.getByRole("button", { name: "고치기" })).toHaveCount(0);
  await expect(row.getByRole("button", { name: "지우기" })).toBeVisible();
});

test("문맥에 실리지 않은 항목에 표시가 보인다", async ({ page }) => {
  await showStubMemories(page, () => [
    stub(920, "너무 긴 항목", {
      content: "가".repeat(200),
      alwaysInject: true,
      omittedFromContext: true,
    }),
    stub(921, "실린 항목", { alwaysInject: true }),
  ]);
  await expect(
    memoryRow(page, "너무 긴 항목").getByTestId("memory-omitted"),
  ).toHaveText("길어서 답에 포함되지 않음");
  await expect(
    memoryRow(page, "실린 항목").getByTestId("memory-omitted"),
  ).toHaveCount(0);
});
