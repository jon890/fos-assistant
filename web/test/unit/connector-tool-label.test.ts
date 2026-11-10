import assert from "node:assert/strict";
import test from "node:test";
import { type ConnectorTool, toolPolicyLabel } from "../../web/src/lib/connection.ts";

/** 쓰기 도구 하나다. 검사마다 달라지는 칸만 덮어쓴다. */
function tool(change: Partial<ConnectorTool>): ConnectorTool {
  return { name: "write_note", title: null, risk: "WRITE", approval: "REQUIRED", grant: true, ...change };
}

test("승인이 없는 도구는 바로 실행한다고 보인다", () => {
  assert.equal(toolPolicyLabel(tool({ risk: "READ", approval: "NONE", grant: false })), "바로 실행해요");
});

test("상시 허락을 줄 수 있는 승인 도구는 실행 전에 묻는다고 보인다", () => {
  assert.equal(toolPolicyLabel(tool({ grant: true })), "실행 전에 물어봐요");
});

test("상시 허락을 닫은 승인 도구는 실행할 때마다 묻는다고 보인다", () => {
  assert.equal(toolPolicyLabel(tool({ grant: false })), "실행할 때마다 물어봐요");
});

test("막힌 도구는 상시 허락 선언과 상관없이 아직 쓸 수 없다고 보인다", () => {
  for (const blocked of [
    tool({ approval: "ALWAYS", grant: false }),
    tool({ risk: "DESTRUCTIVE", approval: "ALWAYS", grant: false }),
    tool({ risk: "FINANCIAL", approval: "ALWAYS", grant: false }),
    // 선언이 틀려 참이 와도 쓸 수 없는 도구가 앞선다.
    tool({ approval: "ALWAYS", grant: true }),
  ]) {
    assert.equal(toolPolicyLabel(blocked), "아직 쓸 수 없어요", JSON.stringify(blocked));
  }
});
