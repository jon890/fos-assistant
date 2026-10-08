import assert from "node:assert/strict";
import test from "node:test";
import { connectionErrorMessage } from "../../web/src/lib/connection.ts";

test("하나에만 붙는 연결을 이미 붙인 경우 뗀 뒤 붙이라고 알린다", () => {
  assert.equal(
    connectionErrorMessage("CONNECTOR_SINGLE_BINDING"),
    "이 연결은 다른 에이전트에 붙어 있어요. 그 에이전트에서 뗀 뒤 붙여 주세요.",
  );
});

test("실행 공간이 없는 에이전트에 붙이면 관리자에게 알리라고 안내한다", () => {
  assert.equal(
    connectionErrorMessage("AGENT_SANDBOX_UNAVAILABLE"),
    "격리된 실행 공간이 준비된 에이전트에만 붙일 수 있어요. 관리자에게 알려 주세요.",
  );
});

test("표에 없는 코드는 기본 문구다", () => {
  assert.equal(
    connectionErrorMessage("NO_SUCH_CODE"),
    "요청을 처리하지 못했어요.",
  );
  assert.equal(connectionErrorMessage(""), "요청을 처리하지 못했어요.");
});
