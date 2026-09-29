/**
 * 사용자가 이름만 넣어 에이전트를 만들고, 그룹에 공개하고, 지우는 흐름을 본다.
 *
 * <p>만든 에이전트는 지운 상태로 남는다. 뒤의 시나리오가 목록을 셀 때 섞이지 않게 한다.
 */
import { call, expect, expectStatus, step, type Context, type Scenario } from "../harness.ts";

type AgentView = { code: string; name: string; visibility: string; editable: boolean; ownedByMe: boolean };
type AdminAgentView = { code: string; hermesProfile: string };
type Turn = { conversationId: string; assistantText: string };

export const agentLifecycleScenario: Scenario = {
  name: "사용자 에이전트 만들기와 지우기",

  async run(context) {
    step("kid 가 이름만 넣어 에이전트를 만든다");
    const created = expectStatus(
      await call(context, "/agents", {
        method: "POST",
        token: context.tokens.kid,
        body: { name: "숙제 도우미" },
      }),
      201,
      "에이전트 만들기",
    ).json<AgentView>();
    expect(created.name === "숙제 도우미", `만든 에이전트의 이름이 다르다: ${created.name}`);
    expect(created.visibility === "PRIVATE", `공개 범위 기본값이 PRIVATE 가 아니다: ${created.visibility}`);
    expect(created.editable && created.ownedByMe, "만든 사람이 관리하는 주인으로 보이지 않는다");

    const profile = expectStatus(
      await call(context, "/admin/agents", { token: context.tokens.dad }),
      200,
      "관리 목록",
    ).json<AdminAgentView[]>().find((agent) => agent.code === created.code)?.hermesProfile;
    expect(profile !== undefined, "관리 목록에 만든 에이전트가 없다");
    expect(context.hermes.profiles().includes(profile!), `대시보드에 profile ${profile} 이 만들어지지 않았다`);

    step("만든 에이전트로 대화 한 번이 답으로 끝난다");
    const question = "받아쓰기 연습을 도와줘";
    const turn = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.kid,
        body: { text: question, agentCode: created.code },
      }),
      200,
      "만든 에이전트와 대화",
    ).json<Turn>();
    expect(turn.assistantText.includes(question), `답에 보낸 문장이 없다: ${turn.assistantText}`);

    step("비공개인 동안 dad 의 목록에 없다");
    expect(
      !(await listOf(context, context.tokens.dad)).some((agent) => agent.code === created.code),
      "비공개 에이전트가 다른 사용자의 목록에 보인다",
    );

    step("그룹 공개로 바꾸면 dad 의 목록에 보인다");
    const shared = expectStatus(
      await call(context, `/agents/${created.code}/visibility`, {
        method: "PATCH",
        token: context.tokens.kid,
        body: { visibility: "GROUP" },
      }),
      200,
      "그룹 공개로 바꾸기",
    ).json<AgentView>();
    expect(shared.visibility === "GROUP" && shared.ownedByMe, "그룹으로 바꾼 뒤 주인이 남지 않았다");
    const inDadList = (await listOf(context, context.tokens.dad)).find((agent) => agent.code === created.code);
    expect(inDadList !== undefined, "그룹 공개 에이전트가 dad 의 목록에 없다");
    expect(!inDadList!.ownedByMe, "dad 가 kid 의 에이전트 주인으로 보인다");

    step("지우면 둘 다의 목록에서 빠지고 그 대화에 보내면 AGENT_NOT_FOUND 다");
    expectStatus(
      await call(context, `/agents/${created.code}`, { method: "DELETE", token: context.tokens.kid }),
      204,
      "에이전트 지우기",
    );
    for (const [who, token] of [["kid", context.tokens.kid], ["dad", context.tokens.dad]] as const) {
      expect(
        !(await listOf(context, token)).some((agent) => agent.code === created.code),
        `지운 에이전트가 ${who} 의 목록에 남았다`,
      );
    }
    const rejected = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.kid,
        body: { conversationId: turn.conversationId, text: "아직 있니?" },
      }),
      404,
      "지운 에이전트의 대화에 보내기",
    );
    expect(
      rejected.json<{ code: string }>().code === "AGENT_NOT_FOUND",
      `지운 에이전트의 오류 코드가 다르다: ${rejected.body}`,
    );
    expect(!context.hermes.profiles().includes(profile!), `지운 에이전트의 profile ${profile} 이 대시보드에 남았다`);
  },
};

async function listOf(context: Context, token: string): Promise<AgentView[]> {
  return expectStatus(await call(context, "/agents", { token }), 200, "에이전트 목록").json<AgentView[]>();
}
