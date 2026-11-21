import assert from "node:assert/strict";
import test from "node:test";
import {
  isOAuthOutcome,
  isOAuthReason,
  oauthOutcomeMessage,
  providerName,
  recallOAuthStart,
  rememberOAuthStart,
  safeAuth,
  safeCompletion,
  safeRevoke,
  safeStart,
} from "../../src/lib/connection-oauth.ts";
test("제공자 이름은 화면 이름으로 바꾸고 모르는 이름은 그대로 쓴다", () => {
  assert.equal(providerName("github"), "GitHub");
  assert.equal(providerName("google"), "Google");
  assert.equal(providerName("toss"), "toss");
  assert.equal(providerName(null), "서비스");
});

test("연결 방식이 없거나 틀리면 직접 입력으로 읽는다", () => {
  const manual = { method: "manual", provider: null, reconnectRequired: false };
  assert.deepEqual(safeAuth(undefined), manual);
  assert.deepEqual(safeAuth({ method: "manual", provider: null, reconnectRequired: true }), manual);
  assert.deepEqual(safeAuth({ method: "oauth", provider: "Bad Name" }), manual);
  assert.deepEqual(safeAuth({ method: "oauth", provider: "github", reconnectRequired: true }), {
    method: "oauth",
    provider: "github",
    reconnectRequired: true,
  });
});

test("해제 결과는 두 상태만 받고 https 가 아닌 주소는 버린다", () => {
  assert.equal(safeRevoke(null), null);
  assert.equal(safeRevoke({ status: "unknown" }), null);
  assert.deepEqual(
    safeRevoke({
      status: "failed",
      settingsUrl: "https://github.com/settings/applications",
      installSettingsUrl: "http://example.com/install",
    }),
    { status: "failed", settingsUrl: "https://github.com/settings/applications", installSettingsUrl: null },
  );
});

test("시작 응답의 로그인 주소는 https 만 받는다", () => {
  assert.deepEqual(safeStart({ authorizeUrl: "https://github.com/apps/x/installations/new?state=s" }), {
    authorizeUrl: "https://github.com/apps/x/installations/new?state=s",
  });
  assert.throws(() => safeStart({ authorizeUrl: "javascript:alert(1)" }));
  assert.throws(() => safeStart({}));
});

test("돌아온 결과는 정한 값만 받고, 다시 보낼 주소가 없는 REAUTHORIZE 는 거절한다", () => {
  assert.deepEqual(safeCompletion({ outcome: "CONNECTED", connectorId: "career", token: "x" }), {
    outcome: "CONNECTED",
    connectorId: "career",
    authorizeUrl: null,
    reason: null,
  });
  assert.equal(safeCompletion({ outcome: "INVALID_STATE", connectorId: "../x" }).connectorId, null);
  assert.equal(safeCompletion({ outcome: "VERIFY_FAILED", reason: "bad reason" }).reason, null);
  assert.throws(() => safeCompletion({ outcome: "REAUTHORIZE", authorizeUrl: null }));
  assert.throws(() => safeCompletion({ outcome: "SOMETHING" }));
});

test("결과 검색어는 정한 값만 받는다", () => {
  assert.equal(isOAuthOutcome("DENIED"), true);
  assert.equal(isOAuthOutcome("denied"), false);
  assert.equal(isOAuthReason("CONNECTOR_CREDENTIAL_REJECTED"), true);
  assert.equal(isOAuthReason("<script>"), false);
});

test("실패한 결과마다 까닭을 보이고 성공은 까닭이 없다", () => {
  assert.equal(oauthOutcomeMessage("CONNECTED", null), null);
  assert.match(oauthOutcomeMessage("DENIED", null) ?? "", /^연결하지 않았어요/);
  assert.equal(oauthOutcomeMessage("APP_REJECTED", null), "관리자 설정 문제로 연결하지 못했어요.");
  assert.equal(oauthOutcomeMessage("SCOPE_DENIED", null), "요청한 권한을 모두 허락해야 연결돼요.");
  assert.equal(
    oauthOutcomeMessage("VERIFY_FAILED", "CONNECTOR_UNAVAILABLE"),
    "연결을 확인하지 못했어요. 서비스에 닿지 못했어요. 잠시 뒤 다시 해 주세요.",
  );
});

test("시작 기억은 같은 플러그인의 15분 안 값만 되살리고 틀린 칸과 에이전트 번호는 버린다", () => {
  const store = new Map<string, string>();
  const storage = {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
  };
  const globals = globalThis as { window?: unknown };
  globals.window = { sessionStorage: storage };
  try {
    rememberOAuthStart("career", { repo: "me/site" }, "browserswitch");
    assert.deepEqual(recallOAuthStart("career"), { values: { repo: "me/site" }, agent: "browserswitch" });
    assert.equal(recallOAuthStart("gmail"), null);
    store.set(
      "connector-oauth-start",
      JSON.stringify({ id: "career", values: { "BAD KEY": "x", repo: 1 }, agent: "../x", at: Date.now() }),
    );
    assert.deepEqual(recallOAuthStart("career"), { values: {}, agent: null });
    store.set("connector-oauth-start", JSON.stringify({ id: "career", values: {}, agent: null, at: Date.now() - 16 * 60 * 1000 }));
    assert.equal(recallOAuthStart("career"), null);
  } finally {
    delete globals.window;
  }
});
