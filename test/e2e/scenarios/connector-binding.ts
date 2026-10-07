/**
 * 사용자가 한 번 연결한 계정을 자기 비공개 에이전트에 붙여, 그 에이전트가 커넥터 도구를 직접 부르는 흐름을 본다(ADR-083).
 *
 * <p>붙이기는 대역의 보관 파일 값을 그 에이전트 profile 에 바인딩 설치로 옮긴다. 새 서버를 더한 붙이기는 재시작이 필요 없어
 * Control Plane 이 반영 지연 뒤 스스로 확인해 쓸 수 있게 하고, 값 교체처럼 재시작 대기가 된 바인딩만 관리자가 공유 gateway 를
 * 재시작한 뒤 반영 완료를 눌러야 한다. 대역이 그 profile 의 hook 처럼 도구 호출마다 Control Plane 에 판정을 묻는다. 연결의 주인은
 * 관리자가 아닌 kid 다. 관리자인 dad 가 남의 에이전트에 붙이거나 떼지 못하는 것을 함께 보기 위해서다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import {
  CONNECTOR_ARGUMENT_SAMPLE,
  CONNECTOR_RESULT_SAMPLE,
  CONNECTOR_TOOL_PROBE,
  DEMO_CONNECTOR,
  DEMO_TOKEN_OK,
  type ConnectorToolCall,
} from "../fake-hermes.ts";
import { readEventStream } from "../../../web/src/lib/stream.ts";
import {
  awaitReady,
  bind,
  confirm,
  connect,
  ConnectorSetup,
  connectionPath,
  DEMO_VALUES,
  probeTool,
  requestNumber,
  useConnectorPolicy,
  type AgentConnectionsView,
  type ConnectionView,
} from "../connector-support.ts";

type ActionView = { actionId: string; connectorId: string; status: string };
type Message = { role: "USER" | "ASSISTANT" | "SYSTEM"; content: string; executionId: number | null;
  activity: { toolCount: number } | null };
type ToolEventView = {
  eventType: string;
  toolName: string | null;
  durationMs: number | null;
  failed: boolean | null;
  detail: string | null;
};
type ExecutionTree = { root: { events: ToolEventView[] } };
type StreamEvent = { type: string; toolName?: string; detail?: string; executionId?: number };

const CONNECTION = connectionPath(DEMO_CONNECTOR.id);
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;
const CONTROL_PLANE_MCP = "fos-assistant";
/** 연결은 쓸 수 있는데 그 에이전트에 붙인 것이 아직 반영되지 않은 호출에 Control Plane 이 답하는 글이다. 판정은 `NOT_READY` 다. */
const BINDING_PENDING = "이 에이전트에 붙인 연결이 아직 반영되지 않았다. 대개 몇 분 안에 저절로 반영되니 사용자에게 잠시 뒤 다시 시도하라고 알린다.";
const BINDING_RESTART = "이 에이전트에 붙인 연결은 관리자가 반영을 마쳐야 쓸 수 있다. 지금은 실행하지 않았으니 사용자에게 관리자의 반영을 기다리라고 알린다.";
/** 그 실행의 에이전트에 그 서버를 붙인 연결이 없어 줄을 남기지 않고 막은 호출의 글이다. */
const NO_CONTEXT = "이 도구 호출의 실행 맥락을 확인하지 못해 실행하지 않았다.";
const APPROVAL_PREFIX = "이 동작은 사용자의 승인이 필요하다. 승인 요청 번호는 ";

async function connectionsOf(context: Context, token: string, agentCode: string): Promise<AgentConnectionsView> {
  return expectStatus(
    await call(context, `/agents/${agentCode}/connections`, { token }),
    200,
    "에이전트의 연결 목록",
  ).json<AgentConnectionsView>();
}

/** 승인한 결과의 알림 줄과 자동 turn 의 답이 그 대화에 이어질 때까지 기다린다. 그 turn 이 끝나야 에이전트를 지울 수 있다. */
async function awaitDelivered(context: Context, token: string, conversationId: string): Promise<void> {
  const deadline = Date.now() + 10_000;
  let last: Message[] = [];
  while (Date.now() < deadline) {
    last = expectStatus(
      await call(context, `/chat/conversations/${conversationId}/messages`, { token }),
      200,
      "승인 요청이 나온 대화의 이력",
    ).json<Message[]>();
    if (last.length >= 2 && last.at(-2)!.role === "SYSTEM" && last.at(-1)!.role === "ASSISTANT") return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`승인 결과가 10초 안에 대화에 이어지지 않았다: ${JSON.stringify(last.map((m) => [m.role, m.content]))}`);
}

function expectCode(response: { body: string; json<T>(): T }, code: string, what: string): void {
  expect(response.json<{ code: string }>().code === code, `${what} 의 오류 코드가 ${code} 가 아니다\n${response.body}`);
}

/** 붙은 서버의 도구 호출 하나를 정책이 허용하는 입력이다. 인자와 결과의 가짜 값이 사건에 실린다. */
function boundToolInput(): string {
  return `${CONNECTOR_TOOL_PROBE}\n${PREFIX}list_scopes ${JSON.stringify({ query: CONNECTOR_ARGUMENT_SAMPLE })}`;
}

/**
 * 그 실행의 도구 사건에 붙은 서버의 도구 호출이 시작과 완료로 남았는지 본다. 요청자가 `MEMBER` 라 `detail` 은 비어 있고(ADR-038),
 * 응답 전체에 외부 서비스의 가짜 값이 없어야 한다. 저장하는 쪽의 가리기는 사건 스트림 시험이 본다.
 */
async function expectBoundToolRecorded(context: Context, token: string, executionId: number, what: string): Promise<void> {
  const treeResponse = expectStatus(
    await call(context, `/usage/executions/${executionId}/tree`, { token }), 200, `${what} 의 실행 트리`,
  );
  const bound = treeResponse.json<ExecutionTree>().root.events.filter((event) => event.toolName === `${PREFIX}list_scopes`);
  expect(
    JSON.stringify(bound.map((event) => [event.eventType, event.detail, event.failed]))
      === JSON.stringify([["TOOL_STARTED", null, null], ["TOOL_COMPLETED", null, false]]),
    `${what}: 붙은 서버의 도구 호출이 시작과 완료로 남지 않았거나 내용이 실렸다: ${JSON.stringify(bound)}`,
  );
  expect(bound[1]!.durationMs === 50, `${what}: 완료 사건의 걸린 시간이 대역이 보낸 50ms 가 아니다: ${bound[1]!.durationMs}`);
  for (const sample of [CONNECTOR_ARGUMENT_SAMPLE, CONNECTOR_RESULT_SAMPLE]) {
    expect(!treeResponse.body.includes(sample), `${what}: 실행 트리 응답에 외부 서비스의 값이 있다`);
  }
}

export const connectorBindingScenario: Scenario = {
  name: "연결 붙이기",

  async run(context) {
    useConnectorPolicy(context);
    const owner = context.tokens.kid;
    const setup = new ConnectorSetup(context, owner);
    let hookOff: string | undefined;
    let failed = false;
    try {
      step("사용자가 연결을 등록하고 비공개 에이전트를 만든다");
      await setup.connect();
      const agent = await setup.createAgent("메모 비서");
      const mine = (): ConnectorToolCall[] =>
        context.hermes.connectorToolCalls().filter((entry) => entry.profile === agent.profile);

      step("붙이면 재시작이 필요 없는 PENDING 이고 대역이 보관 파일로 바인딩 설치를 받고 그 profile 의 도구 목록에 서버 이름이 더해지며 Control Plane MCP 가 남는다");
      const requestsAtBind = context.hermes.connectorRequests().length;
      const bound = await bind(context, owner, agent.code, DEMO_CONNECTOR.id);
      expect(
        bound.bound && bound.status === "PENDING" && !bound.restartRequired && bound.connectionStatus === "READY",
        `붙인 바인딩이 재시작이 필요 없는 PENDING 이 아니다: ${JSON.stringify(bound)}`,
      );
      const bindRequests = context.hermes.connectorRequests().slice(requestsAtBind);
      expect(
        bindRequests.join(" | ") === `bind ${agent.profile}`,
        `붙이기 동안 그 profile 의 바인딩 설치 하나만 받아야 한다: ${bindRequests.join(" | ")}`,
      );
      // 대역은 보관 파일이 없거나 다른 커넥터의 것인 바인딩 설치를 400 으로 거절한다. 붙은 것으로 남았으면 `bind.vault` 를 받은 것이다.
      expect(
        context.hermes.boundConnectorsOf(agent.profile).join() === DEMO_CONNECTOR.id,
        `대역에 붙은 커넥터가 시험 커넥터 하나가 아니다: ${context.hermes.boundConnectorsOf(agent.profile).join()}`,
      );
      const boundToolsets = context.hermes.apiServerToolsetsOf(agent.profile) ?? [];
      expect(
        boundToolsets.includes(DEMO_CONNECTOR.mcp_server) && boundToolsets.includes(CONTROL_PLANE_MCP),
        `붙인 profile 의 도구 목록에 서버 이름과 Control Plane MCP 가 함께 있지 않다: ${boundToolsets.join()}`,
      );
      expect(context.hermes.profileEnv(agent.profile).DEMO_TOKEN === DEMO_TOKEN_OK, "보관 파일의 값이 그 profile 의 env 로 옮겨지지 않았다");
      const pendingList = await connectionsOf(context, owner, agent.code);
      const pendingRow = pendingList.connections.find((connection) => connection.connectorId === DEMO_CONNECTOR.id);
      expect(
        pendingList.blockedReason === null && pendingRow?.bound === true && pendingRow.status === "PENDING"
          && !pendingRow.restartRequired && pendingRow.toolCount === Object.keys(DEMO_CONNECTOR.tools).length,
        `에이전트의 연결 목록이 재시작이 필요 없는 PENDING 을 보이지 않는다: ${JSON.stringify(pendingList)}`,
      );
      const bodies = [
        JSON.stringify(bound),
        JSON.stringify(pendingList),
        (await call(context, CONNECTION, { token: owner })).body,
        (await call(context, "/connectors", { token: owner })).body,
        (await call(context, "/admin/connections", { token: context.tokens.dad })).body,
      ];
      bodies.forEach((body, index) => {
        expect(!body.includes(DEMO_TOKEN_OK), `응답 ${index} 에 토큰 원문이 있다`);
        expect(!body.includes("DEMO_TOKEN") && !body.includes("DEMO_SCOPE"), `응답 ${index} 에 env 이름이 있다`);
      });
      expect(
        !context.hermes.connectorRequests().some((line) => line.includes(DEMO_TOKEN_OK)),
        "대역의 요청 기록에 토큰 원문이 있다",
      );

      step("반영 전에 그 에이전트의 실행이 커넥터 도구를 부르면 반영 대기로 막히고 커넥터 서버에 닿지 않는다");
      const early = await probeTool(context, owner, agent.code, `${PREFIX}list_scopes`);
      expect(early.answer === `block ${BINDING_PENDING}`, `반영 전 호출이 반영 대기 글로 막히지 않았다: ${early.answer}`);
      expect(mine().length === 0, `반영 전 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("관리자 반영 완료 없이 Control Plane 이 스스로 확인해 READY 가 된다");
      const readyRow = await awaitReady(context, owner, agent.code, DEMO_CONNECTOR.id);
      const connection = expectStatus(await call(context, CONNECTION, { token: owner }), 200, "연결 상태").json<ConnectionView>();
      expect(
        JSON.stringify(connection.bindings.map((binding) => [binding.agentCode, binding.status, binding.restartRequired]))
          === JSON.stringify([[agent.code, "READY", false]]),
        `연결 상태의 붙인 에이전트가 다르다: ${JSON.stringify(connection.bindings)}`,
      );

      step("붙은 연결의 scope 를 비워 다시 등록하면 재시작 대기가 되고 관리자가 반영 완료를 눌러야 READY 로 돌아온다");
      // 처음 등록한 DEMO_VALUES 에서 토큰은 그대로 두고 선택 칸인 scope 만 비운다. 대역의 scope 선택지가 "a" 하나뿐이라
      // 다른 선택지로는 바꿀 수 없다. 이미 있던 서버의 env 가 바뀌는 값 교체이므로 떠 있는 MCP 프로세스가 옛 값을 쥐어 재시작이 필요하다.
      await connect(context, owner, DEMO_CONNECTOR.id, { token: DEMO_VALUES.token });
      const restartRow = (await connectionsOf(context, owner, agent.code)).connections
        .find((row) => row.connectorId === DEMO_CONNECTOR.id);
      expect(
        restartRow?.bound === true && restartRow.status === "PENDING" && restartRow.restartRequired,
        `값을 바꿔 다시 등록한 바인딩이 재시작 대기가 아니다: ${JSON.stringify(restartRow)}`,
      );
      const waiting = await probeTool(context, owner, agent.code, `${PREFIX}list_scopes`);
      expect(waiting.answer === `block ${BINDING_RESTART}`, `재시작 대기 바인딩의 호출이 관리자 반영 글로 막히지 않았다: ${waiting.answer}`);
      expect(mine().length === 0, `재시작 대기 중 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);
      const confirmedRow = await confirm(context, agent.code, DEMO_CONNECTOR.id);
      expect(confirmedRow.status === "READY", `관리자 반영 완료 뒤 READY 가 아니다: ${JSON.stringify(confirmedRow)}`);

      step("같은 에이전트의 실행이 읽기 도구를 부르면 허용되어 커넥터 서버에 닿는다");
      const read = await probeTool(context, owner, agent.code, `${PREFIX}list_scopes`);
      expect(read.answer === "allow", `읽기 도구가 허용되지 않았다: ${read.answer}`);
      expect(
        JSON.stringify(mine())
          === JSON.stringify([{ profile: agent.profile, hermesTool: `${PREFIX}list_scopes`, argsJson: "{}", via: "hook" }]),
        `list_scopes 호출 하나만 닿아야 한다: ${JSON.stringify(mine())}`,
      );

      step("쓰기 도구는 승인 줄이 되고 승인하면 대역의 실행 경로가 그 에이전트의 profile 로 한 번 불린다");
      const noteArgs = JSON.stringify({ text: "붙인 연결로 남길 글" });
      const asked = await probeTool(context, owner, agent.code, `${PREFIX}write_note`, noteArgs);
      const actionId = requestNumber(asked.answer);
      expect(
        asked.answer.startsWith(`block ${APPROVAL_PREFIX}`) && actionId !== undefined,
        `write_note 가 승인 요청 번호와 함께 막히지 않았다: ${asked.answer}`,
      );
      expect(mine().length === 1, `승인하기 전인데 write_note 가 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);
      const approved = expectStatus(
        await call(context, `/connector-actions/${actionId}/approve`, { method: "POST", token: owner, body: { grant: null } }),
        200,
        "승인",
      ).json<ActionView>();
      expect(
        approved.status === "SUCCEEDED" && approved.connectorId === DEMO_CONNECTOR.id,
        `승인한 줄이 시험 커넥터의 SUCCEEDED 가 아니다: ${JSON.stringify(approved)}`,
      );
      const executed = mine().filter((entry) => entry.via === "execute");
      expect(
        executed.length === 1 && executed[0]!.hermesTool === `${PREFIX}write_note`
          && JSON.stringify(JSON.parse(executed[0]!.argsJson)) === JSON.stringify(JSON.parse(noteArgs)),
        `실행 경로가 그 profile 로 write_note 를 한 번 부르지 않았다: ${JSON.stringify(mine())}`,
      );
      await awaitDelivered(context, owner, asked.conversationId);

      step("연결이 붙은 에이전트를 그룹 공개로 바꾸려 하면 AGENT_CONNECTIONS_REQUIRE_PRIVATE 이고 비공개로 남는다");
      const shared = expectStatus(
        await call(context, `/agents/${agent.code}/visibility`, { method: "PATCH", token: owner, body: { visibility: "GROUP" } }),
        409,
        "그룹 공개로 바꾸기",
      );
      expectCode(shared, "AGENT_CONNECTIONS_REQUIRE_PRIVATE", "그룹 공개로 바꾸기");
      expect(
        (await connectionsOf(context, owner, agent.code)).blockedReason === null,
        "거절된 공개 변경 뒤 에이전트가 비공개가 아니다",
      );

      step("다른 사용자와 관리자는 그 에이전트에 붙이거나 떼지 못한다");
      const path = `/agents/${agent.code}/connections/${DEMO_CONNECTOR.id}`;
      for (const method of ["PUT", "DELETE"]) {
        const foreign = expectStatus(await call(context, path, { method, token: context.tokens.aunt }), 404, `다른 사용자의 ${method}`);
        expectCode(foreign, "AGENT_NOT_FOUND", `다른 사용자의 ${method}`);
        const admin = expectStatus(await call(context, path, { method, token: context.tokens.dad }), 403, `관리자의 ${method}`);
        expectCode(admin, "FORBIDDEN", `관리자의 ${method}`);
      }
      const untouched = (await connectionsOf(context, owner, agent.code)).connections
        .find((row) => row.connectorId === DEMO_CONNECTOR.id);
      expect(
        untouched?.bound === true && untouched.status === "READY"
          && context.hermes.boundConnectorsOf(agent.profile).join() === DEMO_CONNECTOR.id,
        `남의 붙이기와 떼기가 바인딩을 바꿨다: ${JSON.stringify(untouched)}`,
      );

      step("정책 hook 이 꺼지면 연결 확인이 그 바인딩을 PENDING 으로 내리고 다시 켜면 READY 로 돌아온다");
      context.hermes.setPolicyHook(agent.profile, false);
      hookOff = agent.profile;
      const offCheck = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: owner }), 200, "hook 이 꺼진 연결 확인",
      ).json<ConnectionView>();
      expect(
        offCheck.status === "READY" && offCheck.bindings.find((binding) => binding.agentCode === agent.code)?.status === "PENDING",
        `hook 이 꺼졌는데 연결은 READY 이고 바인딩은 PENDING 이 아니다: ${JSON.stringify(offCheck)}`,
      );
      const offProbe = await probeTool(context, owner, agent.code, `${PREFIX}list_scopes`);
      expect(offProbe.answer === `block ${BINDING_PENDING}`, `PENDING 바인딩의 호출이 반영 대기 글로 막히지 않았다: ${offProbe.answer}`);
      context.hermes.setPolicyHook(agent.profile, true);
      hookOff = undefined;
      const onCheck = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: owner }), 200, "hook 이 켜진 연결 확인",
      ).json<ConnectionView>();
      expect(
        onCheck.bindings.find((binding) => binding.agentCode === agent.code)?.status === "READY",
        `hook 이 켜졌는데 바인딩이 READY 로 돌아오지 않았다: ${JSON.stringify(onCheck)}`,
      );

      step("붙은 에이전트가 한 번에 받는 경로로 도구를 부르면 작업 과정에 그 호출이 남고 내용은 실리지 않는다");
      const once = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST", token: owner, body: { text: boundToolInput(), agentCode: agent.code },
        }),
        200,
        "한 번에 받는 도구 호출 대화",
      ).json<{ conversationId: string; executionId: number }>();
      await expectBoundToolRecorded(context, owner, once.executionId, "한 번에 받는 경로");
      const onceMessages = expectStatus(
        await call(context, `/chat/conversations/${once.conversationId}/messages`, { token: owner }),
        200,
        "한 번에 받은 대화의 이력",
      ).json<Message[]>();
      expect(
        (onceMessages.find((message) => message.executionId === once.executionId)?.activity?.toolCount ?? 0) >= 1,
        "한 번에 받은 답에 작업 과정 요약이 붙지 않았다",
      );

      step("스트림으로 받는 경로도 같은 호출을 같은 모양으로 남기고 외부 서비스의 값을 보내지 않는다");
      const streamed = expectStatus(
        await call(context, "/chat/messages/stream", {
          method: "POST", token: owner, body: { text: boundToolInput(), agentCode: agent.code },
        }),
        200,
        "스트림 도구 호출 대화",
      );
      const received: StreamEvent[] = [];
      await readEventStream<StreamEvent>(
        new globalThis.Response(streamed.body, { headers: { "Content-Type": "text/event-stream" } }),
        (event) => received.push(event),
      );
      const streamedDone = received.find((event) => event.type === "done");
      expect(streamedDone?.executionId !== undefined, `스트림에 done 이 없다: ${streamed.body}`);
      expect(
        received.some((event) => event.type === "tool" && event.toolName === `${PREFIX}list_scopes`),
        "스트림에 붙은 서버의 도구 사건이 없다",
      );
      // 답 조각은 대역이 보낸 입력을 되풀이하므로 인자의 가짜 값이 들어 있다. 도구 사건에는 어느 값도 없어야 한다.
      const toolEvents = JSON.stringify(received.filter((event) => event.type === "tool"));
      for (const sample of [CONNECTOR_ARGUMENT_SAMPLE, CONNECTOR_RESULT_SAMPLE]) {
        expect(!toolEvents.includes(sample), "스트림의 도구 사건에 외부 서비스의 값이 있다");
      }
      await expectBoundToolRecorded(context, owner, streamedDone!.executionId!, "스트림 경로");

      step("떼면 바인딩이 사라지고 그 profile 에서 서버 이름과 값이 빠지며 같은 도구 호출이 줄 없이 막힌다");
      const reachedBeforeUnbind = mine().length;
      const requestsAtUnbind = context.hermes.connectorRequests().length;
      expectStatus(await call(context, path, { method: "DELETE", token: owner }), 204, "떼기");
      expect(
        context.hermes.connectorRequests().slice(requestsAtUnbind).join(" | ") === `unbind ${agent.profile}`,
        `떼기 동안 그 profile 의 떼기 하나만 받아야 한다: ${context.hermes.connectorRequests().slice(requestsAtUnbind).join(" | ")}`,
      );
      const unboundToolsets = context.hermes.apiServerToolsetsOf(agent.profile) ?? [];
      expect(
        context.hermes.boundConnectorsOf(agent.profile).length === 0
          && !unboundToolsets.includes(DEMO_CONNECTOR.mcp_server) && unboundToolsets.includes(CONTROL_PLANE_MCP)
          && context.hermes.profileEnv(agent.profile).DEMO_TOKEN === undefined,
        `뗀 profile 에 커넥터가 남았거나 Control Plane MCP 가 빠졌다: ${unboundToolsets.join()}`,
      );
      const unboundRow = (await connectionsOf(context, owner, agent.code)).connections
        .find((row) => row.connectorId === DEMO_CONNECTOR.id);
      expect(
        unboundRow?.bound === false && unboundRow.status === null,
        `뗀 뒤 에이전트의 연결이 붙지 않음이 아니다: ${JSON.stringify(unboundRow)}`,
      );
      const afterRead = await probeTool(context, owner, agent.code, `${PREFIX}list_scopes`);
      expect(afterRead.answer === `block ${NO_CONTEXT}`, `뗀 뒤 읽기 도구가 줄 없이 막히지 않았다: ${afterRead.answer}`);
      const afterWrite = await probeTool(context, owner, agent.code, `${PREFIX}write_note`, noteArgs);
      expect(afterWrite.answer === `block ${NO_CONTEXT}`, `뗀 뒤 쓰기 도구가 줄 없이 막히지 않았다: ${afterWrite.answer}`);
      const afterActions = expectStatus(
        await call(context, `/chat/conversations/${afterWrite.conversationId}/connector-actions`, { token: owner }),
        200,
        "뗀 뒤 대화의 승인 줄",
      ).json<unknown[]>();
      expect(afterActions.length === 0, `뗀 뒤 쓰기 호출이 승인 줄을 남겼다: ${JSON.stringify(afterActions)}`);
      expect(mine().length === reachedBeforeUnbind, `뗀 뒤 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("다시 두 에이전트에 붙인 뒤 연결을 해제하면 붙은 바인딩이 모두 떼어지고 보관 파일이 지워진다");
      const second = await setup.createAgent("일정 비서");
      for (const target of [agent, second]) await bind(context, owner, target.code, DEMO_CONNECTOR.id);
      const requestsAtDisconnect = context.hermes.connectorRequests().length;
      const disconnected = expectStatus(
        await call(context, CONNECTION, { method: "DELETE", token: owner }), 200, "해제",
      ).json<ConnectionView>();
      setup.forget(DEMO_CONNECTOR.id);
      expect(
        disconnected.status === "DISCONNECTED" && disconnected.bindings.length === 0,
        `해제 뒤 연결이 붙은 에이전트 없는 DISCONNECTED 가 아니다: ${JSON.stringify(disconnected)}`,
      );
      const disconnectRequests = context.hermes.connectorRequests().slice(requestsAtDisconnect);
      expect(
        [...disconnectRequests.slice(0, 2)].sort().join(" | ") === [`unbind ${agent.profile}`, `unbind ${second.profile}`].sort().join(" | ")
          && disconnectRequests.length === 3 && disconnectRequests[2] === "vault delete",
        `해제가 두 바인딩을 뗀 뒤 보관 파일을 지우지 않았다: ${disconnectRequests.join(" | ")}`,
      );
      for (const target of [agent, second]) {
        expect(
          context.hermes.boundConnectorsOf(target.profile).length === 0,
          `해제 뒤 ${target.code} 의 profile 에 커넥터가 남았다`,
        );
        const rows = (await connectionsOf(context, owner, target.code)).connections;
        expect(rows.length === 0, `해제 뒤 ${target.code} 의 연결 목록에 연결이 남았다: ${JSON.stringify(rows)}`);
      }
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 대역과 연결과 에이전트를 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      if (hookOff !== undefined) context.hermes.setPolicyHook(hookOff, true);
      await setup.cleanUp(failed);
    }
  },
};
