import { expect, test } from "./fixtures.ts";
import type {
  Page,
  Route,
} from "../../web/node_modules/@playwright/test/index.js";

/** 대역 응답에 쓰는 UUID 다. 번호마다 다른 값을 만든다. */
const uuid = (n: number) =>
  `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;

const ACTION_ID = uuid(1);
const DRAFT_ID = uuid(2);
const SNAPSHOT_ID = uuid(3);
const POLL_MS = 3000;
const HASH = "f".repeat(64);
const JOB_ID = uuid(4);

type Json = Record<string, unknown>;

function summary(conversationId: string, overrides: Json = {}): Json {
  return {
    snapshotId: SNAPSHOT_ID,
    draftId: DRAFT_ID,
    revision: 3,
    conversationId,
    status: "PREVIEW_READY",
    stage: null,
    saveClicked: false,
    errorCode: null,
    ...overrides,
  };
}

function action(overrides: Json = {}): Json {
  return {
    actionId: ACTION_ID,
    connectorId: "naver-blog",
    toolName: "save_draft",
    title: "블로그 임시저장",
    risk: "WRITE",
    status: "PENDING",
    argsJson: JSON.stringify({ snapshotId: SNAPSHOT_ID }),
    resultText: null,
    errorCode: null,
    createdAt: "2026-10-12T00:00:00Z",
    expiresAt: "2026-10-12T01:00:00Z",
    grantAllowed: false,
    hiddenArgs: false,
    conversationGrantAllowed: false,
    contentDraft: null,
    ...overrides,
  };
}

function draftAction(
  conversationId: string,
  status: string,
  draftStatus: string,
): Json {
  return action({
    status,
    contentDraft: summary(conversationId, { status: draftStatus }),
  });
}

function job(n: number, status = "SUCCEEDED"): Json {
  return {
    snapshotId: uuid(1000 + n),
    draftId: DRAFT_ID,
    revision: n,
    actionId: uuid(2000 + n),
    jobId: uuid(3000 + n),
    status,
    stage: null,
    saveClicked: status === "SUCCEEDED",
    lastCheckedAt: "2026-10-12T00:00:00Z",
    errorCode: null,
  };
}

const SNAPSHOT = {
  snapshotId: SNAPSHOT_ID,
  draftId: DRAFT_ID,
  revision: 3,
  snapshotHash: HASH,
  preview: {
    schemaVersion: 1,
    draftId: DRAFT_ID,
    revision: 3,
    conversationId: "",
    title: "가을 소풍",
    category: "일상",
    tags: ["소풍", "가족"],
    blocks: [
      { type: "paragraph", text: "<b>굵게</b> 쓰지 않은 문단" },
      { type: "image", assetId: "11", caption: "도시락" },
      { type: "sticker", code: "smile" },
      { type: "map", name: "공원", address: "서울" },
    ],
  },
  assetIds: ["11"],
  omittedAssetIds: ["12"],
  expiresAt: "2026-10-12T01:00:00Z",
};

/** 새 대화를 만들어 그 공개 식별자를 돌려준다. */
async function createConversation(page: Page, label: string) {
  const created = await page.request.post("/api/chat", {
    data: { text: `${label} ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  return ((await created.json()) as { conversationId: string }).conversationId;
}

/**
 * 승인 줄과 원고 job 목록, snapshot, job 읽기를 대역한다.
 * 상태를 바꾸는 요청(POST)은 따로 세어 재접속과 폴링이 저장을 다시 보내지 않는지 본다.
 */
async function routeDraft(
  page: Page,
  conversationId: string,
  state: {
    actions: Json[];
    pages?: Record<string, { items: Json[]; next: string | null }>;
    snapshot?: { status: number; json: Json };
    jobs?: Json[];
    events?: string;
  },
) {
  const counts = { snapshot: 0, job: 0, posts: 0, cursors: [] as string[] };
  const base = `**/api/chat/conversations/${conversationId}`;
  page.on("request", (request) => {
    if (
      request.method() === "POST" &&
      /connector-actions|content-draft/.test(request.url())
    )
      counts.posts += 1;
  });
  let eventReads = 0;
  await page.route(`${base}/events`, async (route: Route) => {
    eventReads += 1;
    if (!state.events || eventReads > 1) return new Promise(() => {});
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream; charset=utf-8" },
      body: state.events,
    });
  });
  await page.route(`${base}/connector-actions`, (route: Route) =>
    route.fulfill({ json: state.actions }),
  );
  await page.route(`${base}/content-draft-jobs**`, (route: Route) => {
    const before = new URL(route.request().url()).searchParams.get(
      "beforeSnapshotId",
    );
    counts.cursors.push(before ?? "");
    const pageOf = state.pages?.[before ?? ""] ?? { items: [], next: null };
    return route.fulfill({
      json: { items: pageOf.items, nextBeforeSnapshotId: pageOf.next },
    });
  });
  await page.route(
    `${base}/content-drafts/${DRAFT_ID}/snapshots/${SNAPSHOT_ID}`,
    (route: Route) => {
      counts.snapshot += 1;
      const answer = state.snapshot ?? { status: 200, json: SNAPSHOT };
      return route.fulfill({ status: answer.status, json: answer.json });
    },
  );
  await page.route(
    `${base}/content-drafts/${DRAFT_ID}/snapshots/${SNAPSHOT_ID}/job`,
    (route: Route) => {
      counts.job += 1;
      const queue = state.jobs ?? [];
      const next = queue.length > 1 ? queue.shift()! : queue[0];
      return route.fulfill({ json: next });
    },
  );
  return counts;
}

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: "ignoreErrors" });
});

test("승인 전 원고는 버전과 넣는 사진, 넣지 않는 사진을 보이고 허락 단추 없이 승인한 뒤 끝나면 폴링을 멈춘다", async ({
  page,
}) => {
  await page.clock.install();
  const conversationId = await createConversation(page, "원고 미리보기");
  const state = {
    actions: [draftAction(conversationId, "PENDING", "PREVIEW_READY")],
    jobs: [
      { ...job(3, "SAVING"), actionId: ACTION_ID },
      { ...job(3, "SUCCEEDED"), actionId: ACTION_ID },
    ],
  };
  const counts = await routeDraft(page, conversationId, state);
  let body: unknown;
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      body = route.request().postDataJSON();
      state.actions = [draftAction(conversationId, "EXECUTING", "DISPATCHED")];
      return route.fulfill({ json: state.actions[0] });
    },
  );
  await page.goto(`/chat/${conversationId}`);

  const card = page.getByTestId("content-draft-card");
  await expect(card.getByTestId("content-draft-revision")).toHaveText(
    "원고 3번째 버전",
  );
  const preview = card.getByTestId("content-draft-preview");
  await expect(preview).toContainText("가을 소풍");
  await expect(preview).toContainText("분류 일상 · 태그 소풍, 가족");
  await expect(preview).toContainText("<b>굵게</b> 쓰지 않은 문단");
  await expect(preview.locator("b")).toHaveCount(0);
  await expect(card.getByTestId("content-draft-assets")).toHaveText(
    "넣는 사진: 사진 11",
  );
  await expect(card.getByTestId("content-draft-omitted")).toHaveText(
    "넣지 않는 사진: 사진 12",
  );
  await expect(
    card.getByTestId("content-draft-omitted").getByRole("link"),
  ).toHaveAttribute(
    "href",
    `/api/chat/conversations/${conversationId}/attachments/12`,
  );
  await expect(card).not.toContainText(HASH);
  for (const id of ["approval-grant", "approval-conversation-grant"])
    await expect(page.getByTestId(id)).toHaveCount(0);
  await expect(page.getByTestId("approval-approve-all")).toHaveCount(0);

  await card.getByTestId("content-draft-approve").click();
  await expect(card).toHaveAttribute("data-status", "SAVING");
  expect(body).toEqual({ grant: null });
  await page.clock.runFor(POLL_MS);
  await expect(card).toHaveAttribute("data-status", "SUCCEEDED");
  await expect(card.getByTestId("content-draft-result")).toHaveText(
    "임시저장했어요. 블로그의 임시저장 글에서 확인해 주세요.",
  );
  await expect(card).not.toContainText(JOB_ID);
  const jobReads = counts.job;
  await page.clock.runFor(POLL_MS * 3);
  expect(counts.job).toBe(jobReads);
  expect(counts.posts).toBe(1);
});

for (const [name, status, code] of [
  ["문구를 고쳐 바뀐 원고는", 409, "CONTENT_DRAFT_STALE"],
  ["남의 snapshot 은", 404, "CONVERSATION_NOT_FOUND"],
  ["지워진 원고는", 410, "CONTENT_DRAFT_GONE"],
] as const) {
  test(`${name} 본문과 단추 없이 새 미리보기 안내만 보인다`, async ({
    page,
  }) => {
    const conversationId = await createConversation(page, name);
    await routeDraft(page, conversationId, {
      actions: [draftAction(conversationId, "PENDING", "PREVIEW_READY")],
      snapshot: { status, json: { code } },
    });
    await page.goto(`/chat/${conversationId}`);

    const card = page.getByTestId("content-draft-card");
    await expect(card).toHaveAttribute("data-status", "STALE");
    await expect(card.getByTestId("content-draft-result")).toHaveText(
      "원고가 바뀌었거나 볼 수 없어요. 새 미리보기를 만들어 주세요.",
    );
    await expect(card.getByTestId("content-draft-preview")).toHaveCount(0);
    await expect(card.getByTestId("content-draft-approve")).toHaveCount(0);
    await expect(card.getByTestId("content-draft-reject")).toHaveCount(0);
    await expect(card).not.toContainText(code);
  });
}

for (const [status, text] of [
  [
    "UNKNOWN",
    "임시저장했는지 확인하지 못했어요. 블로그의 임시저장 글에서 직접 확인해 주세요.",
  ],
  ["FAILED", "지금은 저장할 수 없어요. 원고는 그대로 남아 있어요."],
] as const) {
  test(`${status} 원고에는 다시 저장 단추가 없다`, async ({ page }) => {
    const conversationId = await createConversation(page, `원고 ${status}`);
    const counts = await routeDraft(page, conversationId, {
      actions: [draftAction(conversationId, status, status)],
    });
    await page.goto(`/chat/${conversationId}`);

    const card = page.getByTestId("content-draft-card");
    await expect(card.getByTestId("content-draft-result")).toHaveText(text);
    await expect(card.getByRole("button")).toHaveText(["닫기"]);
    expect(counts.job).toBe(0);
    expect(counts.posts).toBe(0);
  });
}

test("승인 응답이 실패로 끝나면 저장할 수 없다는 안내로 끝나고 다른 저장을 보내지 않는다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "원고 미지원");
  const state = {
    actions: [draftAction(conversationId, "PENDING", "PREVIEW_READY")],
  };
  const counts = await routeDraft(page, conversationId, state);
  await page.route(
    `**/api/connector-actions/${ACTION_ID}/approve`,
    (route: Route) => {
      state.actions = [
        action({
          status: "FAILED",
          errorCode: "not_executable",
          contentDraft: summary(conversationId),
        }),
      ];
      return route.fulfill({ json: state.actions[0] });
    },
  );
  await page.goto(`/chat/${conversationId}`);

  const card = page.getByTestId("content-draft-card");
  await card.getByTestId("content-draft-approve").click();
  await expect(card.getByTestId("content-draft-result")).toHaveText(
    "지금은 저장할 수 없어요. 원고는 그대로 남아 있어요.",
  );
  await expect(card.getByRole("button")).toHaveText(["닫기"]);
  await expect(card).not.toContainText("not_executable");
  expect(counts.posts).toBe(1);
});

test("재접속과 중복 승인 사건에서는 GET 만 보내고 끝난 카드를 유지한다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "원고 재접속");
  const event = `data: ${JSON.stringify({ type: "approval", conversationId, detail: ACTION_ID })}\n\n`;
  const counts = await routeDraft(page, conversationId, {
    actions: [draftAction(conversationId, "EXECUTING", "SAVING")],
    jobs: [{ ...job(3, "SUCCEEDED"), actionId: ACTION_ID }],
    events: event + event,
  });
  await page.goto(`/chat/${conversationId}`);

  const card = page.getByTestId("content-draft-card");
  await expect(card).toHaveAttribute("data-status", "SUCCEEDED");
  await page.reload();
  await expect(card).toHaveAttribute("data-status", "SUCCEEDED");
  expect(counts.job).toBeGreaterThan(0);
  expect(counts.snapshot).toBe(0);
  expect(counts.posts).toBe(0);
});

test("일반 승인 줄에 밀린 끝난 원고도 job 목록에서 다시 찾고 일반 묶음은 그대로다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "원고 재발견");
  // 일반 승인 줄 목록은 최근 20개만 준다. 원고 줄은 그 밖으로 밀렸다.
  const general = Array.from({ length: 20 }, (_, index) =>
    action({
      actionId: uuid(5000 + index),
      connectorId: "demo-notes",
      toolName: "write_note",
      title: "메모 쓰기",
      grantAllowed: true,
      argsJson: JSON.stringify({ title: `메모 ${index}` }),
    }),
  );
  const pending = draftAction(conversationId, "PENDING", "PREVIEW_READY");
  await routeDraft(page, conversationId, {
    // 같은 도구 이름의 원고 줄도 일반 묶음에 섞이지 않는다.
    actions: [
      ...general,
      { ...pending, toolName: "write_note", connectorId: "demo-notes" },
    ],
    pages: { "": { items: [job(1)], next: null } },
  });
  await page.goto(`/chat/${conversationId}`);

  const group = page.getByTestId("approval-group");
  await expect(group).toHaveAttribute("data-count", "20");
  await group.getByTestId("approval-group-toggle").click();
  await expect(group.getByTestId("approval-approve-all")).toBeVisible();
  await expect(group.getByTestId("approval-grant").first()).toBeVisible();
  const cards = page.getByTestId("content-draft-card");
  await expect(cards).toHaveCount(2);
  await expect(cards.nth(0)).toHaveAttribute("data-status", "PREVIEW_READY");
  await expect(cards.nth(1)).toHaveAttribute("data-status", "SUCCEEDED");
  await expect(cards.nth(1).getByTestId("content-draft-revision")).toHaveText(
    "원고 1번째 버전",
  );
});

test("원고 job 이 21개면 이전 저장 더 보기로 오래된 원고를 찾고 같은 actionId 는 한 장만 그린다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "원고 다음 페이지");
  const recent = Array.from({ length: 20 }, (_, index) => job(21 - index));
  const cursor = String(recent[19].snapshotId);
  const counts = await routeDraft(page, conversationId, {
    // 승인 줄 목록에 있는 원고는 job 목록에 다시 나와도 한 장이다.
    actions: [
      action({
        actionId: recent[0].actionId,
        status: "SUCCEEDED",
        contentDraft: {
          ...summary(conversationId),
          ...recent[0],
          status: "SUCCEEDED",
        },
      }),
    ],
    pages: {
      "": { items: recent, next: cursor },
      // 다음 페이지가 앞 페이지의 줄을 다시 주어도 한 장이다.
      [cursor]: { items: [recent[5], job(1)], next: null },
    },
  });
  await page.goto(`/chat/${conversationId}`);

  const cards = page.getByTestId("content-draft-card");
  await expect(cards).toHaveCount(20);
  await page.getByTestId("content-draft-more").click();
  await expect(cards).toHaveCount(21);
  await expect(cards.last().getByTestId("content-draft-revision")).toHaveText(
    "원고 1번째 버전",
  );
  await expect(page.getByTestId("content-draft-more")).toHaveCount(0);
  expect(counts.cursors).toContain(cursor);
  expect(counts.posts).toBe(0);
});

test("원고 job 목록이 비면 원고 카드와 더 보기 단추가 없다", async ({
  page,
}) => {
  const conversationId = await createConversation(page, "원고 빈 목록");
  const counts = await routeDraft(page, conversationId, { actions: [] });
  await page.goto(`/chat/${conversationId}`);

  await expect.poll(() => counts.cursors.length).toBeGreaterThan(0);
  await expect(page.getByTestId("approval-list")).toHaveCount(0);
  await expect(page.getByTestId("content-draft-more")).toHaveCount(0);
});
