import assert from "node:assert/strict";
import test from "node:test";
import {
  openNotification,
  openTarget,
  safeNextPath,
  signinPathFor,
  type MarkReadResult,
} from "../../src/lib/push-open.ts";
import type { NotificationView } from "../../src/lib/notification.ts";

// 푸시를 누르면 여는 `/n/<id>` 의 판단을 본다(docs/features/attention/spec.md 의 REQ6).

const NOTIFICATION_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const CONVERSATION_ID = "11111111-2222-4333-8444-555555555555";

function view(overrides: Partial<NotificationView>): NotificationView {
  return {
    id: NOTIFICATION_ID,
    kind: "APPROVAL_REQUESTED",
    title: "승인을 기다리는 요청이 있어요",
    body: "메모 쓰기",
    targetType: "CONVERSATION",
    targetId: CONVERSATION_ID,
    createdAt: "2026-11-20T03:00:00Z",
    readAt: "2026-11-20T03:01:00Z",
    ...overrides,
  };
}

test("승인 요청 푸시를 누르면 읽음을 한 번 부르고 그 대화의 할 일 탭으로 간다", async () => {
  const calls: string[] = [];
  const target = await openNotification(NOTIFICATION_ID, async (id) => {
    calls.push(id);
    return { ok: true, data: view({}) };
  });
  assert.deepEqual(calls, [NOTIFICATION_ID]);
  assert.equal(target, `/chat/${CONVERSATION_ID}?todo=open`);
});

test("공개 식별자 모양이 아니면 읽음을 부르지 않고 알림 목록으로 간다", async () => {
  let called = false;
  const target = await openNotification("12", async () => {
    called = true;
    return { ok: false, status: 404 };
  });
  assert.equal(called, false);
  assert.equal(target, "/notifications");
});

test("갈 곳이 없거나 읽음이 실패하면 알림 목록으로 가고 예약 작업은 그 작업으로 간다", () => {
  const cases: [MarkReadResult, string][] = [
    [{ ok: true, data: view({ kind: "DEFAULT_TOOLS_PENDING", targetType: null, targetId: null }) }, "/notifications"],
    [{ ok: false, status: 404 }, "/notifications"],
    [{ ok: false, status: 502 }, "/notifications"],
    [{ ok: true, data: view({ kind: "TASK_SUCCEEDED", targetType: "TASK", targetId: "t1" }) }, "/tasks/t1"],
    [{ ok: true, data: view({ kind: "EVENT_WATCH_FAILED", targetType: "CONNECTIONS", targetId: null }) }, "/connections"],
  ];
  for (const [result, expected] of cases) assert.equal(openTarget(result), expected, JSON.stringify(result));
});

test("로그인 주소는 열기 주소를 next 로 인코딩해 붙인다", () => {
  assert.equal(signinPathFor(NOTIFICATION_ID), `/signin?next=%2Fn%2F${NOTIFICATION_ID}`);
});

test("로그인 뒤 돌아갈 주소는 같은 사이트 경로만 받는다", () => {
  assert.equal(safeNextPath(`/n/${NOTIFICATION_ID}`), `/n/${NOTIFICATION_ID}`);
  assert.equal(safeNextPath("/chat/abc?todo=open"), "/chat/abc?todo=open");
  for (const value of [
    undefined,
    "",
    ["/n/a", "/n/b"],
    "//evil.example",
    "/\\evil.example",
    "https://evil.example/",
    "javascript:alert(1)",
    // Next 가 한 번 디코딩한 값이다. 다시 풀지 않으므로 경로로 시작하지 않아 거절된다.
    "%2F%2Fevil.example",
    "%E0",
    "/\tevil",
    "/\n/evil.example",
    "/\u007f",
  ])
    assert.equal(safeNextPath(value as string | string[] | undefined), "/", JSON.stringify(value));
});
