/**
 * 연결용 에이전트의 커넥터 도구 호출이 Control Plane 의 판정을 거치는지 전체 흐름으로 본다.
 *
 * <p>대역이 profile 플러그인의 hook 처럼 도구 호출마다 판정을 묻고, 허용된 호출만 커넥터 서버에 닿은 것으로 친다.
 * 앞의 커넥터 연결 시나리오가 해제로 끝나므로 여기서 다시 등록하고 끝에서 해제한다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";
import { CONNECTOR_TOOL_PROBE, DEMO_CONNECTOR, DEMO_TOKEN_OK } from "../fake-hermes.ts";

type ConnectionView = { status: string; agentCode: string | null };
type Turn = { assistantText: string };

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;
const PREFIX = `mcp__${DEMO_CONNECTOR.mcp_server}__`;

/** 연결용 에이전트에게 도구 호출 한 줄을 보내고 대역이 답한 판정 줄을 돌려준다. */
async function probe(context: Context, agentCode: string, hermesTool: string, argsJson: string): Promise<string> {
  const turn = expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { text: `${CONNECTOR_TOOL_PROBE}\n${hermesTool} ${argsJson}`, agentCode },
    }),
    200,
    `${hermesTool} 호출 대화`,
  ).json<Turn>();
  const line = turn.assistantText.split("\n").find((candidate) => candidate.startsWith(`${hermesTool}: `));
  expect(line !== undefined, `답에 ${hermesTool} 의 판정 줄이 없다: ${turn.assistantText}`);
  return line!.slice(`${hermesTool}: `.length);
}

export const connectorPolicyScenario: Scenario = {
  name: "커넥터 도구 정책",

  async run(context) {
    context.hermes.setConnectorPolicy(`${context.api.replace(/\/api\/v1$/, "")}/internal/hermes/connector-policy`);
    let profile: string | undefined;
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
      profile = installLine!.split(" ")[1]!;
      const ready = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인",
      ).json<ConnectionView>();
      expect(ready.status === "READY" && ready.agentCode !== null, `READY 가 아니다: ${JSON.stringify(ready)}`);
      const agentCode = ready.agentCode!;
      expect(
        (context.hermes.profileEnv(profile).MCP_FOS_ASSISTANT_API_KEY ?? "") !== "",
        "연결용 profile 의 env 에 MCP 토큰이 없다. hook 이 판정을 물을 때 쓰는 토큰이다",
      );
      const mine = (): { hermesTool: string; argsJson: string }[] =>
        context.hermes.connectorToolCalls().filter((entry) => entry.profile === profile);
      expect(mine().length === 0, `호출하기 전인데 커넥터 서버에 닿은 호출이 있다: ${JSON.stringify(mine())}`);

      step("승인이 없는 읽기 도구는 허용되어 커넥터 서버에 닿는다");
      const listed = await probe(context, agentCode, `${PREFIX}list_scopes`, "{}");
      expect(listed === "allow", `list_scopes 가 허용되지 않았다: ${listed}`);
      expect(
        JSON.stringify(mine()) === JSON.stringify([{ profile, hermesTool: `${PREFIX}list_scopes`, argsJson: "{}" }]),
        `list_scopes 호출 하나만 닿아야 한다: ${JSON.stringify(mine())}`,
      );

      step("승인이 필요한 쓰기 도구는 지금은 기록만 하고 허용된다");
      const noteArgs = JSON.stringify({ text: "안녕" });
      const written = await probe(context, agentCode, `${PREFIX}write_note`, noteArgs);
      expect(written === "allow", `write_note 가 허용되지 않았다: ${written}`);
      expect(
        mine().length === 2 && mine()[1]!.hermesTool === `${PREFIX}write_note` && mine()[1]!.argsJson === noteArgs,
        `write_note 호출이 인자 그대로 닿아야 한다: ${JSON.stringify(mine())}`,
      );

      step("선언하지 않은 도구와 파괴적인 도구는 글과 함께 막히고 커넥터 서버에 닿지 않는다");
      for (const tool of ["hidden_tool", "purge_notes"]) {
        const answer = await probe(context, agentCode, `${PREFIX}${tool}`, "{}");
        expect(answer.startsWith("block "), `${tool} 이 막히지 않았다: ${answer}`);
        expect(answer.slice("block ".length).trim() !== "", `${tool} 을 막은 글이 비었다`);
        expect(!answer.includes("정책을 확인하지 못했다"), `${tool} 을 Control Plane 의 판정이 아닌 까닭으로 막았다: ${answer}`);
      }
      expect(mine().length === 2, `막힌 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("다른 서버의 등록 이름은 선언한 도구와 이름이 같아도 막힌다");
      const foreign = await probe(context, agentCode, "mcp__other__list_scopes", "{}");
      expect(foreign.startsWith("block "), `다른 서버의 도구가 막히지 않았다: ${foreign}`);
      expect(mine().length === 2, `다른 서버의 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);

      step("정책 hook 이 꺼져 PENDING 이 되면 연결용 에이전트가 꺼져 대화가 거절되고 도구 호출이 없다");
      context.hermes.setPolicyHook(profile, false);
      const hookOff = expectStatus(
        await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "hook 이 꺼진 연결 확인",
      ).json<ConnectionView>();
      expect(hookOff.status === "PENDING", `hook 이 꺼졌는데 PENDING 이 아니다: ${JSON.stringify(hookOff)}`);
      const submitsBefore = context.hermes.submitCount();
      const refused = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: `${CONNECTOR_TOOL_PROBE}\n${PREFIX}list_scopes {}`, agentCode },
        }),
        409,
        "꺼진 연결용 에이전트와의 대화",
      );
      expect(refused.json<{ code: string }>().code === "AGENT_DISABLED", `오류 코드가 다르다\n${refused.body}`);
      expect(context.hermes.submitCount() === submitsBefore, "꺼진 에이전트의 대화가 Hermes 에 제출됐다");
      expect(mine().length === 2, `PENDING 인 연결의 호출이 커넥터 서버에 닿았다: ${JSON.stringify(mine())}`);
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      // 어디서 실패해도 대역과 연결을 되돌린다. 정리가 실패해도 원래 실패를 가리지 않는다.
      if (profile !== undefined) context.hermes.setPolicyHook(profile, true);
      try {
        expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
      } catch (cleanupError) {
        if (!failed) throw cleanupError;
      }
    }
  },
};
