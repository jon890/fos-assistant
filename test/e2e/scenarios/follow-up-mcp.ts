/**
 * 에이전트가 대화 중에 `follow_up_propose` 로 할 일을 제안하고, 같은 제안과 거절한 제안이 되풀이되지 않는 것을 실제 대화 turn 으로 본다.
 *
 * <p>계약은 `docs/features/attention.md` 의 「제안 도구」 와 「제안 억제」 가 갖는다. 주인과 대화는 서명한 `_fos_ctx` 로 찾은
 * origin 실행에서 정한다(ADR-032). 가짜 Hermes 가 run 마다 그 run 의 session 으로 서명해 부른다.
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { FOLLOW_UP_PROPOSE_PROBE } from "../fake-hermes.ts";

export const FOLLOW_UP_MCP_PROFILE = "follow-up-mcp";

const AGENT_CODE = "follow-up-mcp";
const TITLE = "할 일 검사 7391";
const PROBE = `${FOLLOW_UP_PROPOSE_PROBE} ${JSON.stringify({ title: TITLE })}`;
const CREATED = "할 일로 제안했다";
const DUPLICATE = "같은 할 일이 이미 있다";
const DECLINED = "사용자가 이 할 일을 거절했다";

type Turn = { conversationId: string; executionId: number; assistantText: string };
type FollowUp = { id: string; title: string; status: string; conversationId: string | null; proposed: boolean };

/** 아빠가 제안 검사 글을 보낸다. 대화 번호를 주면 그 대화에 이어 보낸다. */
async function sendProposeProbe(context: Context, conversationId?: string): Promise<Turn> {
  return expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: conversationId === undefined
        ? { text: PROBE, agentCode: AGENT_CODE }
        : { conversationId, text: PROBE, agentCode: AGENT_CODE },
    }),
    200,
    "할 일 제안 검사 대화",
  ).json<Turn>();
}

async function followUps(context: Context, token: string): Promise<FollowUp[]> {
  return expectStatus(await call(context, "/follow-ups", { token }), 200, "할 일 목록").json<FollowUp[]>();
}

export const followUpMcpScenario: Scenario = {
  name: "할 일 제안 도구",

  async run(context) {
    step("관리자가 GROUP 에이전트를 새 profile 로 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT_CODE,
          name: "Follow-up MCP",
          hermesProfile: FOLLOW_UP_MCP_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${FOLLOW_UP_MCP_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "GROUP",
          ownerEmail: null,
        },
      }),
      200,
      "할 일 제안 에이전트 등록",
    );

    // 에이전트를 등록한 뒤로는 어디서 실패해도 정리한다. 정리가 실패해도 원래 실패를 가리지 않는다.
    let issued: { id: number; token: string } | undefined;
    let failed = false;
    try {
      step("그 profile 에 묶은 토큰을 발급해 가짜 Hermes 에 준다");
      issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: FOLLOW_UP_MCP_PROFILE, label: "follow-up-mcp-e2e" },
        }),
        200,
        "할 일 제안 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      context.hermes.setMemoryReadMcp(context.api.replace(/\/api\/v1$/, "") + "/mcp", issued.token);

      step("아빠의 대화에서 제안하면 그 대화의 PROPOSED 할 일이 생긴다");
      const first = await sendProposeProbe(context);
      expect(first.assistantText.startsWith(CREATED), `제안의 답이 다르다: ${first.assistantText}`);
      const proposed = (await followUps(context, context.tokens.dad)).filter((item) => item.title === TITLE);
      expect(proposed.length === 1, `제안한 할 일이 한 줄이 아니다: ${JSON.stringify(proposed)}`);
      const created = proposed[0]!;
      expect(created.status === "PROPOSED", `제안한 할 일의 상태가 다르다: ${created.status}`);
      expect(created.proposed, "제안한 할 일이 제안으로 표시되지 않았다");
      expect(created.conversationId === first.conversationId,
        `제안한 할 일의 대화가 다르다: ${created.conversationId} != ${first.conversationId}`);

      step("같은 대화에서 같은 제안을 다시 하면 새로 만들지 않는다");
      const again = await sendProposeProbe(context, first.conversationId);
      expect(again.assistantText.startsWith(DUPLICATE), `되풀이한 제안의 답이 다르다: ${again.assistantText}`);
      const afterAgain = (await followUps(context, context.tokens.dad)).filter((item) => item.title === TITLE);
      expect(afterAgain.length === 1, `되풀이한 제안이 새 줄을 만들었다: ${JSON.stringify(afterAgain)}`);

      step("거절한 뒤 같은 대화에서 다시 제안하면 거절한 적이 있다고 답한다");
      expectStatus(
        await call(context, `/follow-ups/${created.id}/reject`, { method: "POST", token: context.tokens.dad }),
        200,
        "제안 거절",
      );
      const declined = await sendProposeProbe(context, first.conversationId);
      expect(declined.assistantText.startsWith(DECLINED), `거절한 제안의 답이 다르다: ${declined.assistantText}`);

      step("아이의 할 일 목록에 아빠의 제안이 없다");
      const kids = await followUps(context, context.tokens.kid);
      expect(kids.every((item) => item.id !== created.id), "아이의 목록에 아빠의 제안이 보인다");
    } catch (error) {
      failed = true;
      throw error;
    } finally {
      const cleanupErrors: unknown[] = [];
      const cleanup = async (action: () => Promise<unknown>) => {
        try {
          await action();
        } catch (error) {
          cleanupErrors.push(error);
        }
      };
      const tokenId = issued?.id;
      if (tokenId !== undefined) {
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "할 일 제안 MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, `/admin/agents/${AGENT_CODE}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: { enabled: false, visibility: "GROUP", ownerEmail: null },
        }),
        200,
        "할 일 제안 에이전트 끄기",
      ));
      // finally 에서 던지면 원래 실패가 사라지므로, 원래 실패가 없을 때만 정리 실패를 알린다.
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
