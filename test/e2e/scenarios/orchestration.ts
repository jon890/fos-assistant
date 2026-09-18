/** 흐름이 붙은 에이전트 하나가 실행 넷을 남기고 나무로 조회되는지 본다. */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";

/** 흐름 전용 에이전트가 쓰는 profile 이다. 러너가 이 이름으로 key 파일을 만든다. */
export const FLOW_BINDING = {
  agentCode: "flow",
  profileName: "flowdad",
} as const;

type ChatEvent = {
  type: "delta" | "tool" | "step" | "done" | "error";
  text?: string;
  stepName?: string;
  stepState?: string;
  conversationId?: number;
  messageId?: number;
  executionId?: number;
  code?: string;
};

type Execution = { id: number; status: string; hasChildren: boolean };
type ExecutionNode = { executionId: number; status: string; children: ExecutionNode[] };
type ExecutionTree = { root: ExecutionNode; truncated: boolean };
type Message = { id: number; role: string; executionId: number | null; hasChildren: boolean };

async function events(response: Response): Promise<ChatEvent[]> {
  const received: ChatEvent[] = [];
  await readEventStream<ChatEvent>(
    new globalThis.Response(response.body, { headers: { "Content-Type": "text/event-stream" } }),
    (event) => received.push(event),
  );
  return received;
}

export const orchestrationScenario: Scenario = {
  name: "다중 에이전트 흐름",

  async run(context) {
    step("모르는 흐름 이름으로는 에이전트를 만들지 못한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: "nosuchflow",
          name: "없는 흐름",
          hermesProfile: FLOW_BINDING.profileName,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${FLOW_BINDING.profileName}`,
          provider: "openai-codex",
          costMode: "API",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "PRIVATE",
          ownerEmail: "dad@example.com",
          flow: "there-is-no-such-flow",
        },
      }),
      400,
      "모르는 흐름으로 만든 에이전트",
    );

    step("흐름을 붙인 에이전트를 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: FLOW_BINDING.agentCode,
          name: "흐름 비서",
          hermesProfile: FLOW_BINDING.profileName,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${FLOW_BINDING.profileName}`,
          provider: "openai-codex",
          costMode: "API",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "PRIVATE",
          ownerEmail: "dad@example.com",
          flow: "research-and-build",
        },
      }),
      200,
      "흐름 에이전트 등록",
    );

    step("흐름으로 대화하면 네 단계 사건이 순서대로 온다");
    const received = await events(expectStatus(
      await call(context, "/chat/messages/stream", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "전기차를 사는 게 나을까?", agentCode: FLOW_BINDING.agentCode },
      }),
      200,
      "흐름 대화",
    ));
    const steps = received
      .filter((event) => event.type === "step")
      .map((event) => `${event.stepName}:${event.stepState}`);
    expect(
      steps.join(",") ===
        [
          "chief:started",
          "chief:completed",
          "researcher:started",
          "engineer:started",
          "researcher:completed",
          "engineer:completed",
          "synthesizer:started",
          "synthesizer:completed",
        ].join(","),
      `네 단계가 순서대로 오지 않았다: ${steps.join(",")}`,
    );
    const done = received.at(-1);
    expect(done?.type === "done", `마지막 사건이 done 이 아니다: ${JSON.stringify(done)}`);

    step("실행 넷이 남고 나무로 조회된다");
    const tree = expectStatus(
      await call(context, `/usage/executions/${done!.executionId}/tree`, {
        token: context.tokens.dad,
      }),
      200,
      "흐름 실행 나무 조회",
    ).json<ExecutionTree>();
    expect(tree.root.executionId === done!.executionId, "답에 붙은 실행이 뿌리가 아니다");
    expect(tree.root.children.length === 3, `자식이 셋이 아니다: ${tree.root.children.length}`);
    expect(
      tree.root.children.every((child) => child.children.length === 0),
      "자식이 다시 자식을 가졌다",
    );
    expect(
      [tree.root, ...tree.root.children].every((node) => node.status === "SUCCEEDED"),
      "실행 넷이 모두 성공으로 남지 않았다",
    );

    step("사용량 목록에는 뿌리만 나온다");
    const listed = expectStatus(
      await call(context, "/usage/executions?limit=50", { token: context.tokens.dad }),
      200,
      "흐름 뒤 실행 목록",
    ).json<Execution[]>();
    const childIds = tree.root.children.map((child) => child.executionId);
    expect(
      listed.some((execution) => execution.id === done!.executionId),
      "뿌리가 목록에 없다",
    );
    expect(
      listed.every((execution) => !childIds.includes(execution.id)),
      "자식 실행이 목록에 섞였다",
    );

    step("대화 이력이 흐름으로 만든 답에만 자식이 있다고 알린다");
    const messages = expectStatus(
      await call(context, `/chat/conversations/${done!.conversationId}/messages`, {
        token: context.tokens.dad,
      }),
      200,
      "흐름 대화 이력",
    ).json<Message[]>();
    const assistant = messages.find((message) => message.role === "ASSISTANT");
    expect(assistant?.hasChildren === true, "흐름으로 만든 답에 자식 표시가 없다");
    expect(
      messages.filter((message) => message.role === "USER").every((message) => !message.hasChildren),
      "사용자 메시지에 자식 표시가 붙었다",
    );

    step("Chief 의 답이 계약을 어기면 자식을 만들지 않고 흐름이 실패한다");
    const broken = await events(expectStatus(
      await call(context, "/chat/messages/stream", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "흐름 계약 위반 검사", agentCode: FLOW_BINDING.agentCode },
      }),
      200,
      "계약을 어긴 흐름",
    ));
    const failure = broken.at(-1);
    expect(
      failure?.type === "error" && failure.code === "ORCHESTRATION_CONTRACT_BROKEN",
      `계약 위반 오류가 오지 않았다: ${JSON.stringify(failure)}`,
    );
    expect(
      broken.filter((event) => event.type === "step").map((event) => `${event.stepName}:${event.stepState}`)
        .join(",") === "chief:started,chief:completed,chief:failed",
      "계약을 어긴 뒤 단계 표시가 실패로 바뀌지 않았다",
    );

    step("흐름이 없는 에이전트는 지금처럼 실행 하나만 남긴다");
    const plain = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "흐름 없는 대화", agentCode: "dad" },
      }),
      200,
      "흐름 없는 대화",
    ).json<{ executionId: number }>();
    const plainTree = expectStatus(
      await call(context, `/usage/executions/${plain.executionId}/tree`, {
        token: context.tokens.dad,
      }),
      200,
      "흐름 없는 실행 나무",
    ).json<ExecutionTree>();
    expect(plainTree.root.children.length === 0, "흐름이 아닌 대화에 자식이 생겼다");
  },
};
