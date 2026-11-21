import assert from "node:assert/strict";
import test from "node:test";
import {
  needsAppSlug,
  oauthAppTitle,
  rewriteSummary,
  signingConfirmText,
} from "../../src/lib/connector-oauth-admin-api.ts";
import { notificationHref } from "../../src/lib/notification.ts";

test("OAuth 앱 줄의 제목은 플러그인마다인 앱에만 플러그인 이름을 붙인다", () => {
  const base = { connectorId: "career", connectorTitle: "커리어", clientId: null, appSlug: null, set: false, prefix: null, updatedBy: null, updatedAt: null };
  assert.equal(oauthAppTitle({ ...base, provider: "github" }), "GitHub 앱(커리어)");
  assert.equal(oauthAppTitle({ ...base, provider: "google" }), "Google 앱");
  assert.equal(needsAppSlug("github"), true);
  assert.equal(needsAppSlug("google"), false);
});

test("서명 비밀 새로 만들기는 다시 쓸 연결 수와 결과를 알린다", () => {
  assert.match(signingConfirmText(3), /^단추 연결 3개의 토큰 통로 주소를 다시 써요/);
  assert.deepEqual(rewriteSummary({ total: 0, failed: 0 }), ["새 서명 비밀을 만들었어요."]);
  assert.deepEqual(rewriteSummary({ total: 3, failed: 1 }), [
    "단추 연결 2개의 통로 주소를 다시 썼어요.",
    "1개는 다시 쓰지 못했어요. 새로 만들기를 다시 누르거나 그 연결을 다시 연결해 주세요.",
  ]);
});

test("앱 거절 알림은 서버 비밀값 화면으로, 다시 연결 알림은 외부 서비스 연결로 간다", () => {
  const view = { id: "n1", title: "", body: "", targetId: null, createdAt: "2026-11-21T00:00:00Z", readAt: null };
  assert.equal(
    notificationHref({ ...view, kind: "CONNECTOR_OAUTH_APP_REJECTED", targetType: "ADMIN_SECRETS" }),
    "/admin/secrets",
  );
  assert.equal(
    notificationHref({ ...view, kind: "CONNECTOR_RECONNECT_REQUIRED", targetType: "CONNECTIONS" }),
    "/connections",
  );
});
