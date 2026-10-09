import assert from "node:assert/strict";
import test from "node:test";
import type { AgentView } from "../../web/src/lib/agent.ts";
import {
  agentChoices,
  boundReadiness,
} from "../../web/src/lib/connector-onboarding.ts";

function agent(code: string, overrides: Partial<AgentView> = {}): AgentView {
  return {
    code,
    name: `${code} 비서`,
    visibility: "PRIVATE",
    acceptsAttachments: true,
    editable: true,
    ownedByMe: true,
    connectorManaged: false,
    runsTasks: true,
    ...overrides,
  };
}

test("남의 에이전트와 예전 방식의 연결 에이전트는 후보에서 뺀다", () => {
  const choices = agentChoices(
    [
      agent("mine"),
      agent("shared", { ownedByMe: false, visibility: "GROUP" }),
      agent("legacy", { connectorManaged: true }),
    ],
    [],
    null,
  );
  assert.deepEqual(
    choices.map((choice) => choice.code),
    ["mine"],
  );
});

test("붙일 수 있는 것, 붙은 것, 그룹 공개 순서로 두고 보던 에이전트를 맨 앞에 둔다", () => {
  const agents = [
    agent("group", { visibility: "GROUP" }),
    agent("bound"),
    agent("first"),
    agent("second"),
  ];
  const bindings = [
    {
      agentCode: "bound",
      agentName: "bound 비서",
      status: "READY" as const,
      restartRequired: false,
    },
  ];

  assert.deepEqual(
    agentChoices(agents, bindings, null).map((choice) => [
      choice.code,
      choice.state,
    ]),
    [
      ["first", "BINDABLE"],
      ["second", "BINDABLE"],
      ["bound", "BOUND"],
      ["group", "GROUP"],
    ],
  );
  const preferred = agentChoices(agents, bindings, "second");
  assert.equal(preferred[0].code, "second");
  assert.equal(preferred[0].preferred, true);
  assert.equal(
    preferred.filter((choice) => choice.preferred).length,
    1,
  );
});

test("붙인 직후 언제 쓸 수 있는지 알린다", () => {
  assert.equal(
    boundReadiness({ status: "READY", restartRequired: false }),
    "바로 쓸 수 있어요.",
  );
  assert.equal(
    boundReadiness({ status: "PENDING", restartRequired: false }),
    "몇 분 안에 쓸 수 있어요.",
  );
  assert.equal(
    boundReadiness({ status: "PENDING", restartRequired: true }),
    "관리자가 반영을 확인하면 쓸 수 있어요.",
  );
});
