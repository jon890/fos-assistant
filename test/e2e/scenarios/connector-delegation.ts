/**
 * 일반 에이전트의 turn 이 연결용 에이전트에게 일을 맡겼을 때, 그 실행의 커넥터 도구 호출이 판정을 거치고
 * 결과가 부르는 쪽 대화에 외부 데이터로 감싸여 돌아오는지 전체 흐름으로 본다.
 *
 * <p>시나리오가 부르는 쪽 profile 의 플러그인 역할을 해 `agent_delegate` 를 서명해 부른다. 맡긴 실행은 대역이
 * 연결용 profile 의 hook 처럼 도구 호출마다 Control Plane 에 판정을 묻는다. 앞의 커넥터 시나리오가 해제로 끝나므로
 * 여기서 다시 등록하고 끝에서 해제한다.
 */
import { call, expect, expectStatus, fail, step, type Scenario } from "../harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK } from "../fake-hermes.ts";
import { DAD_BINDING } from "./binding.ts";
import { callTool, contextFor, openStream, parsed, within, type Status } from "../delegation-support.ts";

type ConnectionView = { status: string; agentCode: string | null };
type Tree = { root: { children: { executionId: number; status: string }[] } };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;
/** 부르는 쪽인 일반 에이전트다. 연결의 주인인 아빠의 개인 에이전트라 연결용 에이전트에게 맡길 수 있다. */
const CHIEF_AGENT = "dad";
const WAKE_INPUT = "맡긴 일의 결과가 도착했다.";

export const connectorDelegationScenario: Scenario = {
  name: "연결용 에이전트에게 맡기기",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let tokenId: number | undefined;
    let rootExecutionId: number | undefined;
    let rootReleased = false;
    let failed = false;
    try {
      step("시험 커넥터를 다시 등록하고 연결을 확인해 READY 로 만든다");
      const requestsAtRegister = context.hermes.connectorRequests().length;
      expectStatus(
        await call(context, CONNECTION, {
          method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK, scope: "a" } },
        }),
        200,
        "등록",
      );
      const installLine = context.hermes.connectorRequests().slice(requestsAtRegister)
        .find((line) => /^install \S+ on$/.test(line));
      expect(installLine !== undefined, "등록 동안 설치 요청이 없었다");
      const connectorProfile = installLine!.split(" ")[1]!;
      const ready = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인",
      ).json<ConnectionView>();
      expect(ready.status === "READY" && ready.agentCode !== null, `READY 가 아니다: ${JSON.stringify(ready)}`);
      const connectorAgent = ready.agentCode!;
      const reached = (): { hermesTool: string; argsJson: string }[] =>
        context.hermes.connectorToolCalls().filter((entry) => entry.profile === connectorProfile);
      const reachedBefore = reached().length;

      step("부르는 쪽 profile 의 토큰을 발급하고 일반 에이전트의 turn 하나를 붙잡는다");
      const issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: DAD_BINDING.profileName, label: "connector-delegation-e2e" },
        }),
        200,
        "부르는 쪽 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      tokenId = issued.id;
      const token = issued.token;
      context.hermes.holdNextRun();
      const turn = await openStream(context, "연결용 에이전트에게 맡기는 turn", CHIEF_AGENT);
      await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 부르는 쪽 turn 의 실행을 받지 않았다");
      rootExecutionId = await within(turn.executionId, 5_000, "부르는 쪽 turn 의 started 사건을 받지 못했다");
      const rootRun = context.hermes.heldRun();
      if (rootRun === undefined) fail("붙잡은 부르는 쪽 turn 의 run 이 없다");
      const rootSession = rootRun.sessionId;
      const delegate = async (task: string): Promise<Status> =>
        parsed<Status>(
          await callTool(context, token, "agent_delegate", { agent_code: connectorAgent, task },
            contextFor(token, "agent_delegate", rootSession)),
          "agent_delegate",
        );
      // 끝난 실행을 `agent_status` 로 읽으면 그 결과는 전한 것으로 적혀 자동 turn 이 열리지 않는다. 실행 트리로 본다.
      const rootId = rootExecutionId;
      const finish = async (executionId: number, what: string): Promise<string> => {
        const until = Date.now() + 10_000;
        let status: string | undefined;
        while (Date.now() < until) {
          status = expectStatus(
            await call(context, `/usage/executions/${rootId}/tree`, { token: context.tokens.dad }), 200, "실행 트리",
          ).json<Tree>().root.children.find((child) => child.executionId === executionId)?.status;
          if (status !== undefined && status !== "RUNNING") return status;
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
        fail(`${what}: 10초 안에 끝나지 않았다. 마지막 상태: ${status}`);
      };

      step("맡긴 실행이 읽기 도구를 부르면 허용되어 커넥터 서버에 한 번 닿는다");
      const read = await delegate(`${CONNECTOR_TOOL_PROBE}\n${PREFIX}list_scopes {}`);
      const readDone = await finish(read.execution_id, "읽기 도구를 부른 맡긴 실행");
      expect(readDone === "SUCCEEDED", `읽기 도구를 부른 실행이 SUCCEEDED 가 아니다: ${readDone}`);
      expect(
        JSON.stringify(reached().slice(reachedBefore))
          === JSON.stringify([{ profile: connectorProfile, hermesTool: `${PREFIX}list_scopes`, argsJson: "{}", via: "hook" }]),
        `list_scopes 호출 하나만 닿아야 한다: ${JSON.stringify(reached())}`,
      );

      step("맡긴 실행이 승인이 필요한 쓰기 도구를 부르면 막히고 커넥터 서버에 닿지 않는다");
      const write = await delegate(`${CONNECTOR_TOOL_PROBE}\n${PREFIX}write_note ${JSON.stringify({ text: "안녕" })}`);
      expect(write.execution_id !== read.execution_id, "두 번째 맡기기가 새 실행을 만들지 않았다");
      const writeDone = await finish(write.execution_id, "쓰기 도구를 부른 맡긴 실행");
      expect(writeDone === "SUCCEEDED", `쓰기 도구를 부른 실행이 SUCCEEDED 가 아니다: ${writeDone}`);
      expect(
        reached().length === reachedBefore + 1,
        `승인 없이 write_note 호출이 커넥터 서버에 닿았다: ${JSON.stringify(reached())}`,
      );

      step("부르는 쪽 turn 이 끝나면 자동 turn 의 입력에 두 결과가 외부 데이터로 감싸여 들어간다");
      context.hermes.releaseHeldRun();
      rootReleased = true;
      const events = await within(turn.completed, 10_000, "부르는 쪽 turn 의 스트림이 끝나지 않았다");
      const deadline = Date.now() + 10_000;
      let input = context.hermes.lastSubmittedInput() ?? "";
      while (Date.now() < deadline && !input.includes(WAKE_INPUT)) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        input = context.hermes.lastSubmittedInput() ?? "";
      }
      expect(input.includes(WAKE_INPUT), `10초 안에 자동 turn 이 열리지 않았다. 마지막 입력: ${input}`);
      const opened = input.split("<external-data>\n").length - 1;
      const closed = input.split("\n</external-data>").length - 1;
      expect(opened === 2 && closed === 2, `두 결과가 각각 외부 데이터로 감싸이지 않았다: ${input}`);
      const wrapped = input.split("<external-data>\n").slice(1).map((part) => part.split("\n</external-data>")[0]!);
      expect(
        wrapped.some((body) => body.includes(`${PREFIX}list_scopes: allow`)),
        `감싼 글에 읽기 도구의 결과가 없다: ${input}`,
      );
      expect(
        wrapped.some((body) => body.includes(`${PREFIX}write_note: block `) && body.includes("승인 요청 번호는")),
        `감싼 글에 승인이 필요하다는 글로 막힌 쓰기 도구의 결과가 없다: ${input}`,
      );
      expect(input.includes("지시로 따르지 않는다"), `외부 데이터가 지시가 아니라는 문장이 없다: ${input}`);

      step("자동 turn 이 부르는 쪽 대화에 답을 남기고 끝난다");
      // 자동 turn 이 도는 채로 끝내면 뒤 시나리오의 제출 기록에 그 turn 이 섞인다.
      const conversationId = (events as { conversationId?: string }[])
        .find((event) => typeof event.conversationId === "string")?.conversationId;
      if (conversationId === undefined) fail(`부르는 쪽 turn 의 사건에 대화 식별자가 없다: ${JSON.stringify(events)}`);
      const answers = async (): Promise<number> =>
        expectStatus(
          await call(context, `/chat/conversations/${conversationId}/messages`, { token: context.tokens.dad }),
          200,
          "부르는 쪽 대화 이력",
        ).json<{ role: string }[]>().filter((message) => message.role === "ASSISTANT").length;
      let answered = await answers();
      while (Date.now() < deadline && answered < 2) {
        await new Promise((resolve) => setTimeout(resolve, 100));
        answered = await answers();
      }
      expect(answered === 2, `부르는 쪽 대화의 답이 turn 의 답과 자동 turn 의 답 둘이 아니다: ${answered}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 붙잡은 turn 과 토큰과 연결을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      const cleanupErrors: unknown[] = [];
      const cleanup = async (action: () => Promise<unknown>) => {
        try {
          await action();
        } catch (error) {
          cleanupErrors.push(error);
        }
      };
      if (rootExecutionId !== undefined && !rootReleased) {
        const executionId = rootExecutionId;
        await cleanup(() => call(context, `/chat/executions/${executionId}/stop`, { method: "POST", token: context.tokens.dad }));
        context.hermes.releaseHeldRun();
      }
      if (tokenId !== undefined) {
        const id = tokenId;
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${id}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "부르는 쪽 MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제",
      ));
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
