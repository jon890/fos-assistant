/**
 * 부모 run 이 끝난 뒤에도 하위 에이전트의 `memory_read` 가 origin 사용자의 Memory 에만 닿는지 실제 대화 turn 으로 본다.
 *
 * <p>가짜 Hermes 가 플러그인처럼 부모 run 안에서 자식 session 을 등록한다. 자식의 호출은 `/chat/messages` 가 응답한 뒤
 * 시나리오가 부른다. 자식 session 은 Control Plane 이 정한 값이 아니므로 등록이 없으면 요청자를 찾지 못한다(ADR-037).
 */
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { SUBAGENT_MEMORY_PROBE } from "../fake-hermes.ts";

export const NATIVE_DELEGATION_PROFILE = "native-delegation-group";

const AGENT_CODE = "native-delegation";
const NOT_READABLE = "Memory 항목을 읽을 수 없습니다.";
const INVALID_CONTEXT = "호출 맥락을 확인할 수 없습니다";
const CHILD_ANSWER = /^하위 에이전트 session: (native-[0-9a-f-]+)$/;

type MemoryView = { id: number };
type Turn = { conversationId: string; executionId: number; assistantText: string };
type ExecutionView = { id: number; status: string };

async function send(context: Context, token: string, body: Record<string, string>, what: string): Promise<Turn> {
  return expectStatus(
    await call(context, "/chat/messages", { method: "POST", token, body: { ...body, agentCode: AGENT_CODE } }),
    200,
    what,
  ).json<Turn>();
}

/** 답에서 가짜 Hermes 가 등록한 자식 session 을 꺼낸다. */
function childOf(turn: Turn, who: string): string {
  const child = CHILD_ANSWER.exec(turn.assistantText)?.[1];
  if (child === undefined) fail(`${who}의 답에 자식 session 이 없다: ${turn.assistantText}`);
  return child;
}

async function memoryTitle(context: Context, token: string, title: string): Promise<MemoryView> {
  return expectStatus(
    await call(context, "/memories", {
      method: "POST",
      token,
      body: { scope: "USER", title, content: `${title} 본문`, alwaysInject: false },
    }),
    200,
    `${title} 생성`,
  ).json<MemoryView>();
}

export const nativeDelegationScenario: Scenario = {
  name: "부모가 끝난 뒤의 하위 에이전트 MCP",

  async run(context) {
    step("관리자가 GROUP 에이전트를 새 profile 로 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT_CODE,
          name: "Native delegation",
          hermesProfile: NATIVE_DELEGATION_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${NATIVE_DELEGATION_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "GROUP",
          ownerEmail: null,
        },
      }),
      200,
      "GROUP 에이전트 등록",
    );

    // 에이전트를 등록한 뒤로는 어디서 실패해도 정리한다. 정리가 실패해도 원래 실패를 가리지 않는다.
    let issued: { id: number; token: string } | undefined;
    const memoryIds: { token: string; id: number }[] = [];
    let failed = false;
    try {
      step("그 profile 에 묶은 토큰을 발급해 가짜 Hermes 에 준다");
      issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: NATIVE_DELEGATION_PROFILE, label: "native-delegation-e2e" },
        }),
        200,
        "하위 에이전트 검사 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      context.hermes.setMemoryReadMcp(context.api.replace(/\/api\/v1$/, "") + "/mcp", issued.token);

      step("아빠와 아이가 각자 제목만 싣는 개인 Memory 를 만든다");
      const dadMemory = await memoryTitle(context, context.tokens.dad, "아빠 위임 검사");
      memoryIds.push({ token: context.tokens.dad, id: dadMemory.id });
      const kidMemory = await memoryTitle(context, context.tokens.kid, "아이 위임 검사");
      memoryIds.push({ token: context.tokens.kid, id: kidMemory.id });

      step("두 사용자의 부모 turn 이 끝나면 자식 session 이 각자의 뿌리 아래 등록돼 있다");
      const [dadTurn, kidTurn] = await Promise.all([
        send(context, context.tokens.dad, { text: SUBAGENT_MEMORY_PROBE }, "아빠 하위 에이전트 검사 대화"),
        send(context, context.tokens.kid, { text: SUBAGENT_MEMORY_PROBE }, "아이 하위 에이전트 검사 대화"),
      ]);
      const dadChild = childOf(dadTurn, "아빠");
      const kidChild = childOf(kidTurn, "아이");
      const registrations = context.hermes.subagentRegistrations();
      const dadRegistration = registrations.find((entry) => entry.childSessionId === dadChild);
      const kidRegistration = registrations.find((entry) => entry.childSessionId === kidChild);
      expect(dadRegistration?.status === 201, `아빠 자식의 등록 상태가 201 이 아니다: ${dadRegistration?.status}`);
      expect(kidRegistration?.status === 201, `아이 자식의 등록 상태가 201 이 아니다: ${kidRegistration?.status}`);
      expect(dadRegistration?.rootSessionId !== kidRegistration?.rootSessionId, "두 자식의 뿌리 session 이 같다");

      step("두 turn 의 실행이 끝난 상태다");
      for (const [who, token, turn] of [
        ["아빠", context.tokens.dad, dadTurn],
        ["아이", context.tokens.kid, kidTurn],
      ] as const) {
        const listed = expectStatus(
          await call(context, "/usage/executions?limit=50", { token }),
          200,
          `${who} 사용량 조회`,
        ).json<ExecutionView[]>();
        const execution = listed.find((entry) => entry.id === turn.executionId);
        expect(execution?.status === "SUCCEEDED", `${who} turn 의 실행이 끝나지 않았다: ${execution?.status}`);
      }

      step("부모가 끝난 뒤 각 자식은 자기 origin 사용자의 Memory 만 읽는다");
      const dadOwn = await context.hermes.readMemoryAsSubagent(dadChild, dadMemory.id);
      expect(dadOwn.includes("아빠 위임 검사 본문"), `아빠 자식이 아빠 본문을 읽지 못했다: ${dadOwn}`);
      const dadCross = await context.hermes.readMemoryAsSubagent(dadChild, kidMemory.id);
      expect(dadCross === NOT_READABLE, `아빠 자식이 아이의 Memory 를 읽었다: ${dadCross}`);
      const kidOwn = await context.hermes.readMemoryAsSubagent(kidChild, kidMemory.id);
      expect(kidOwn.includes("아이 위임 검사 본문"), `아이 자식이 아이 본문을 읽지 못했다: ${kidOwn}`);
      const kidCross = await context.hermes.readMemoryAsSubagent(kidChild, dadMemory.id);
      expect(kidCross === NOT_READABLE, `아이 자식이 아빠의 Memory 를 읽었다: ${kidCross}`);

      step("같은 대화의 다음 turn 뒤에도 아빠 자식은 아빠 Memory 를 읽는다");
      await send(
        context,
        context.tokens.dad,
        { conversationId: dadTurn.conversationId, text: "다음 turn 검사" },
        "아빠 다음 turn",
      );
      const afterNext = await context.hermes.readMemoryAsSubagent(dadChild, dadMemory.id);
      expect(afterNext.includes("아빠 위임 검사 본문"), `다음 turn 뒤 아빠 자식이 본문을 읽지 못했다: ${afterNext}`);

      step("등록하지 않은 자식 session 의 호출은 거절된다");
      const unregistered = await context.hermes.readMemoryAsUnregisteredSubagent(
        dadRegistration!.rootSessionId,
        dadMemory.id,
      );
      expect(
        unregistered.startsWith(INVALID_CONTEXT) && !unregistered.includes("아빠 위임 검사 본문"),
        `등록하지 않은 자식의 호출이 거절되지 않았다: ${unregistered}`,
      );
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
      for (const memory of memoryIds) {
        await cleanup(async () => expectStatus(
          await call(context, `/memories/${memory.id}`, { method: "DELETE", token: memory.token }),
          200,
          "하위 에이전트 검사 Memory 정리",
        ));
      }
      const tokenId = issued?.id;
      if (tokenId !== undefined) {
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "하위 에이전트 검사 MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, `/admin/agents/${AGENT_CODE}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: { enabled: false, visibility: "GROUP", ownerEmail: null },
        }),
        200,
        "하위 에이전트 검사 에이전트 끄기",
      ));
      // finally 에서 던지면 원래 실패가 사라지므로, 원래 실패가 없을 때만 정리 실패를 알린다.
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
