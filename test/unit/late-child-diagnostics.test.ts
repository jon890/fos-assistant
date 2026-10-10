import assert from "node:assert/strict";
import test from "node:test";
import { startFakeHermes } from "../e2e/fake-hermes.ts";
import { type ChildUsageObservation } from "../e2e/fake-hermes/state.ts";
import { childUsageDiagnostic } from "../e2e/scenarios/streaming.ts";
import { maskBackendLog, backendFailureExcerpt } from "../e2e/backend-log.ts";

test("늦은 자식 관찰은 GET을 소비하지 않고 첫 정상 GET 뒤에만 종료를 해제한다", async () => {
  const hermes = await startFakeHermes({ example: "example-key" });
  const headers = { Authorization: "Bearer example-key" };
  try {
    const submitted = await fetch(`${hermes.baseUrl}/p/example/v1/runs`, {
      method: "POST", headers, body: JSON.stringify({ input: "자식 늦은 완료 검사" }),
    });
    const { run_id } = await submitted.json();
    const stream = await (await fetch(`${hermes.baseUrl}/p/example/v1/runs/${run_id}/events`, { headers })).text();
    assert.match(stream, /subagent.start/);
    assert.match(stream, /run.completed/);
    assert.doesNotMatch(stream, /subagent.complete/);
    const before = hermes.childUsageObservations()[0]!;
    assert.equal(before.childSessionId, `child-${run_id}`);
    assert.equal(before.reads, 0);
    assert.equal(before.requests.length, 0);
    assert.ok(before.parentStreamClosedAt! >= before.registeredAt);
    assert.equal(hermes.releaseChildUsage(before.childSessionId), false);
    const url = `${hermes.baseUrl}/p/example/api/sessions/${before.childSessionId}`;
    assert.equal((await fetch(url)).status, 401);
    assert.equal(hermes.releaseChildUsage(before.childSessionId), false);
    assert.equal(hermes.childUsageObservations()[0]!.reads, 0);
    const first = (await (await fetch(url, { headers })).json()).session;
    assert.equal(first.ended_at, null);
    const observed = hermes.childUsageObservations()[0]!;
    assert.equal(observed.reads, 1);
    assert.deepEqual(observed.requests.map((request) => [request.status, request.endedAt]), [[401, null], [200, null]]);
    observed.requests[1]!.endedAt = 9999;
    observed.requests.push({ at: "changed", status: 200, endedAt: 9999 });
    observed.reads = 999;
    assert.equal(hermes.childUsageObservations()[0]!.reads, 1);
    assert.equal(hermes.childUsageObservations()[0]!.requests.length, 2);
    assert.equal(hermes.childUsageObservations()[0]!.requests[1]!.endedAt, null);
    // 조회 횟수 자체로 종료되지 않는다. 명시적인 해제 전에는 다음 GET도 미종료다.
    assert.equal((await (await fetch(url, { headers })).json()).session.ended_at, null);
    assert.equal(hermes.releaseChildUsage(before.childSessionId), true);
    const finished = (await (await fetch(url, { headers })).json()).session;
    assert.equal(finished.ended_at, 1002.5);
    assert.equal(finished.input_tokens + finished.cache_read_tokens + finished.cache_write_tokens, 160);
    assert.equal(finished.output_tokens, 20);
    assert.equal((finished.ended_at - finished.started_at) * 1000, 2500);
    const after = hermes.childUsageObservations()[0]!;
    assert.ok(after.releasedAt! >= after.requests[1]!.at);
    assert.equal(after.requests.at(-1)!.endedAt, 1002.5);
  } finally {
    await hermes.close();
  }
});

test("완료 사건이 없는 두 fixture는 첫 GET에서 기존 사용량과 종료 시각을 준다", async () => {
  const hermes = await startFakeHermes({ example: "example-key" });
  const headers = { Authorization: "Bearer example-key" };
  try {
    for (const input of ["자식 완료 사건 없음 검사", "압축 뒤 자식 완료 검사"]) {
      const { run_id } = await (await fetch(`${hermes.baseUrl}/p/example/v1/runs`, {
        method: "POST", headers, body: JSON.stringify({ input }),
      })).json();
      const stream = await (await fetch(`${hermes.baseUrl}/p/example/v1/runs/${run_id}/events`, { headers })).text();
      assert.doesNotMatch(stream, /subagent.complete/);
      const session = (await (await fetch(`${hermes.baseUrl}/p/example/api/sessions/child-${run_id}`, { headers })).json()).session;
      assert.equal(session.ended_at, 1002.5);
      assert.equal(session.input_tokens + session.cache_read_tokens + session.cache_write_tokens, 160);
      assert.equal(session.output_tokens, 20);
      assert.equal(session.parent_session_id.startsWith("compacted-"), input === "압축 뒤 자식 완료 검사");
    }
  } finally {
    await hermes.close();
  }
});

test("실패 단계는 첫 조회·재조회·저장·값 오류를 나누고 마지막 트리의 본문을 제외한다", () => {
  const at = "2026-01-01T00:00:00Z";
  const fixture: ChildUsageObservation = { childSessionId: "child-fixture", parentSessionId: "parent-fixture",
    registeredAt: at, parentStreamClosedAt: at, releasedAt: null, reads: 0, requests: [] };
  type Tree = NonNullable<Parameters<typeof childUsageDiagnostic>[0]>;
  const event: Tree["root"]["events"][number] = { sequence: 1, eventType: "SUBAGENT_STARTED", toolName: null, subagentName: "sa-fixture",
    hermesSessionId: "child-fixture", model: null, inputTokens: null, outputTokens: null, durationMs: null,
    failed: null, detail: "노출하면 안 되는 본문", occurredAt: at, subagentUsageStatus: "PENDING" };
  const tree: Tree = { truncated: false, root: { truncated: false, executionId: 1, agentCode: "dad", status: "SUCCEEDED",
    requestReceivedAt: at, submittedAt: at, firstDeltaAt: at, finishedAt: at, events: [event], children: [] } };
  const diagnostic = () => JSON.parse(childUsageDiagnostic(tree, fixture));
  assert.equal(JSON.parse(childUsageDiagnostic(tree, undefined)).phase, "fixture 등록 없음");
  assert.equal(diagnostic().phase, "첫 session GET 없음");
  fixture.requests.push({ at, status: 401, endedAt: null });
  assert.equal(diagnostic().phase, "정상 session GET 없음");
  fixture.requests.push({ at, status: 200, endedAt: null });
  fixture.reads = 1;
  assert.equal(diagnostic().phase, "두 번째 session GET 없음");
  fixture.requests.push({ at, status: 200, endedAt: 1002.5 });
  fixture.reads = 2;
  assert.equal(diagnostic().phase, "종료 응답 뒤 완료 사건 저장 없음");
  tree.root.events.push({ ...event, sequence: 2, eventType: "SUBAGENT_COMPLETED",
    inputTokens: 159, outputTokens: 21, durationMs: 2501 });
  assert.equal(diagnostic().phase, "완료 사건 값 확인");
  assert.equal(diagnostic().lastTree.root.events[1].inputTokens, 159);
  assert.equal(diagnostic().lastTree.root.events[1].outputTokens, 21);
  assert.equal(diagnostic().lastTree.root.events[1].durationMs, 2501);
  assert.equal(diagnostic().lastTree.root.finishedAt, at);
  assert.equal(diagnostic().fixture.requests[1].at, at);
  assert.doesNotMatch(childUsageDiagnostic(tree, fixture), /노출하면|detail|goal|content/);
  tree.root.events.push({ ...tree.root.events[1]!, sequence: 3 });
  assert.equal(diagnostic().phase, "완료 사건 중복");
});

test("실패 요약은 관련 WARN을 보존하고 로그의 인증·주소·본문을 가린다", () => {
  const warnings = "WARN HttpHermesRunsClient 하위 에이전트 사용량을 읽지 못했다 sessionId=child-fixture\n"
    + "WARN SubagentUsageReconciler 실행 정보 보완을 마치지 못했다 작업=1";
  const secrets = ["example-key", "example-private-value"];
  const log = warnings + "\n" + "ERROR at com.bifos.example\n".repeat(60)
    + 'Authorization: Bearer example-key\n{"input":"개인 입력", "output":"개인 답"}\n'
    + "text=한 줄 개인 본문\ntoken=short-secret api_key=short-key\n"
    + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJleGFtcGxlIn0.exampleSignature\n"
    + "fos_svc_exampleValue sk-exampleValue ghp_exampleValue\n"
    + "https://example.invalid/private user@example.invalid example-private-value\n";
  const masked = maskBackendLog(log, secrets);
  assert.match(backendFailureExcerpt(masked), /WARN HttpHermesRunsClient/);
  assert.match(backendFailureExcerpt(masked), /WARN SubagentUsageReconciler/);
  assert.match(masked, /sessionId=child-fixture/);
  for (const raw of [...secrets, "개인 입력", "개인 답", "한 줄 개인 본문", "short-secret", "short-key",
    "eyJhbGci", "fos_svc_exampleValue", "sk-exampleValue", "ghp_exampleValue", "example.invalid"]) {
    assert.equal(masked.includes(raw), false, raw);
  }
});
