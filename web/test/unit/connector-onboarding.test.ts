import assert from "node:assert/strict";
import test from "node:test";
import type { AgentView } from "../../src/lib/agent.ts";
import {
  agentChoices,
  boundReadiness,
} from "../../src/lib/connector-onboarding.ts";

function agent(code: string, overrides: Partial<AgentView> = {}): AgentView {
  return {
    code,
    name: `${code} 비서`,
    visibility: "PRIVATE",
    acceptsAttachments: true,
    editable: true,
    ownedByMe: true,
    ...overrides,
  };
}

test("남의 에이전트는 후보에서 뺀다", () => {
  const choices = agentChoices(
    [
      agent("mine"),
      agent("shared", { ownedByMe: false, visibility: "GROUP" }),
      agent("other", { ownedByMe: false }),
    ],
    [],
    null,
  );
  assert.deepEqual(
    choices.map((choice) => choice.code),
    ["mine"],
  );
});

test("기본 비서는 비서에 이미 붙은 연결이라도 후보에서 뺀다(스펙 assistant-hub REQ9)", () => {
  const choices = agentChoices(
    [agent("chief", { primary: true }), agent("shopper")],
    [
      {
        agentCode: "chief",
        agentName: "chief 비서",
        status: "READY" as const,
        restartRequired: false,
      },
    ],
    "chief",
  );
  assert.deepEqual(
    choices.map((choice) => choice.code),
    ["shopper"],
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
  assert.equal(preferred.filter((choice) => choice.preferred).length, 1);
});

test("붙인 직후 언제 쓸 수 있는지 알린다", () => {
  assert.equal(
    boundReadiness({ status: "READY", restartRequired: false }),
    "바로 쓸 수 있어요.",
  );
  assert.equal(
    boundReadiness({ status: "PENDING", restartRequired: false }),
    "몇 분 안에 자동으로 반영돼요.",
  );
  assert.equal(
    boundReadiness({ status: "PENDING", restartRequired: true }),
    "자동으로 반영하고 있어요. 대화가 없는 틈에 반영돼서 조금 걸릴 수 있어요.",
  );
  assert.equal(
    boundReadiness({
      status: "PENDING",
      restartRequired: true,
      applyFailed: true,
    }),
    "반영하지 못했어요. 관리자에게 알렸어요.",
  );
});
