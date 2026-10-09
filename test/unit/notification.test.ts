import assert from "node:assert/strict";
import test from "node:test";
import { notificationHref, unreadBadge, type NotificationView } from "../../web/src/lib/notification.ts";

const CONVERSATION_ID = "0f0e0d0c-0b0a-4908-8706-050403020100";

function view(overrides: Partial<NotificationView> = {}): NotificationView {
  return {
    id: "11111111-2222-4333-8444-555555555555",
    kind: "APPROVAL_REQUESTED",
    title: "승인을 기다리는 요청이 있어요",
    body: "메모 쓰기",
    targetType: "CONVERSATION",
    targetId: CONVERSATION_ID,
    createdAt: "2026-10-01T12:00:00Z",
    readAt: null,
    ...overrides,
  };
}

test("대화가 대상인 알림은 그 대화 주소로 간다", () => {
  assert.equal(notificationHref(view()), `/chat/${CONVERSATION_ID}`);
});

test("작업이 대상인 알림은 그 작업 주소로 간다", () => {
  const taskId = "99999999-8888-4777-8666-555555555555";
  assert.equal(notificationHref(view({ kind: "TASK_FAILED", targetType: "TASK", targetId: taskId })), `/tasks/${taskId}`);
});

test("대상이 없는 알림은 갈 곳이 없다", () => {
  assert.equal(notificationHref(view({ targetType: null, targetId: null })), null);
  assert.equal(notificationHref(view({ targetId: null })), null);
});

test("도구 요청 알림은 관리자 확인 경로로, 결과 알림은 요청자 경로로 간다", () => {
  assert.equal(notificationHref(view({ kind: "TOOLSET_REQUESTED", targetType: "ADMIN_TOOL_REQUEST" })), `/admin/tool-requests/${CONVERSATION_ID}`);
  assert.equal(notificationHref(view({ kind: "TOOLSET_REQUEST_DECIDED", targetType: "TOOLSET_REQUEST" })), `/tool-requests/${CONVERSATION_ID}`);
});

test("연결 다시 설치 알림은 공개 식별자 없이 관리자 연결 반영 확인 목록으로 간다", () => {
  assert.equal(
    notificationHref(view({ kind: "CONNECTOR_REINSTALLED", targetType: "ADMIN_CONNECTIONS", targetId: null })),
    "/admin/connections",
  );
});

test("읽지 않은 알림이 없으면 배지를 그리지 않는다", () => {
  assert.equal(unreadBadge(0), null);
  assert.equal(unreadBadge(-1), null);
});

test("읽지 않은 수는 그대로 보이고 99 를 넘으면 99+ 로 줄인다", () => {
  assert.equal(unreadBadge(5), "5");
  assert.equal(unreadBadge(99), "99");
  assert.equal(unreadBadge(100), "99+");
});
