/**
 * 같은 GROUP 에이전트를 쓰는 두 사용자가 `memory_read` 로 자기 Memory 에만 닿는지 실제 대화 turn 으로 본다.
 *
 * <p>MCP 토큰은 profile 만 증명한다. 두 사용자의 호출이 같은 토큰으로 오므로, 요청자는 서명한 `_fos_ctx` 의 루트
 * session 으로 찾은 도는 실행의 사용자여야 한다(ADR-032). 가짜 Hermes 가 run 마다 그 run 의 session 으로 서명해 부른다.
 *
 * <p>에이전트가 기본 비서가 아닌 대화는 읽기 전용이고(스펙 assistant-hub REQ5), 한 profile 은 한 사람의 기본 비서만 될 수 있다.
 * 그래서 두 사람의 turn 을 한꺼번에 시작하지 못한다. 앞 사람의 실행을 붙잡아 둔 채 기본 비서를 뒷사람으로 옮겨 보내, 뒷사람의
 * 읽기는 두 실행이 함께 도는 동안 일어나게 한다.
 */
import { call, expect, expectStatus, fail, restorePrimaries, seedConversation, step, type Context, type Scenario } from "../harness.ts";
import { within } from "../delegation-support.ts";
import { MEMORY_READ_PROBE } from "../fake-hermes.ts";

export const MCP_PRINCIPAL_PROFILE = "mcp-shared-group";

const AGENT_CODE = "mcp-shared";
const NOT_READABLE = "Memory 항목을 읽을 수 없습니다.\n다음 방법: memory_search 로 다른 항목을 찾는다";

type MemoryView = { id: number };
type Turn = { conversationId: string; executionId: number; assistantText: string };
type ExecutionView = { id: number };

type Probe = { token: string; memoryId: number };

/** 그 사용자가 GROUP 에이전트와의 대화에 Memory 읽기 검사 글을 보낸다. */
async function sendReadProbe(context: Context, probe: Probe, conversationId: string): Promise<Turn> {
  return expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: probe.token,
      body: { conversationId, text: `${MEMORY_READ_PROBE} ${probe.memoryId}` },
    }),
    200,
    "Memory 읽기 검사 대화",
  ).json<Turn>();
}

/**
 * 두 사람이 같은 GROUP 에이전트로 검사 글을 보낸다. 앞 사람의 실행을 붙잡아 두고 뒷사람이 보내므로 뒷사람의 읽기는 두 실행이
 * 함께 도는 동안 일어난다. test-support 대화 만들기가 그 에이전트를 잠시 그 사람의 기본 비서로 둔다.
 */
async function sendOverlapping(context: Context, first: Probe, second: Probe): Promise<[Turn, Turn]> {
  const firstConversation = await seedConversation(context, first.token, AGENT_CODE);
  context.hermes.holdNextRun();
  const firstTurn = sendReadProbe(context, first, firstConversation);
  let released = false;
  try {
    await within(context.hermes.waitForHeldRun(), 5_000, "가짜 Hermes 가 앞 사람의 실행을 받지 않았다");
    await restorePrimaries(context);
    const secondTurn = await sendReadProbe(context, second, await seedConversation(context, second.token, AGENT_CODE));
    released = true;
    context.hermes.releaseHeldRun();
    return [await firstTurn, secondTurn];
  } finally {
    if (!released) {
      try {
        context.hermes.releaseHeldRun();
      } catch {
        // 붙잡은 실행이 없으면 놓을 것도 없다. 원래 실패를 가리지 않는다.
      }
    }
    await firstTurn.catch(() => undefined);
    await restorePrimaries(context);
  }
}

async function executionIds(context: Context, token: string): Promise<number[]> {
  return expectStatus(
    await call(context, "/usage/executions?limit=50", { token }),
    200,
    "사용량 조회",
  ).json<ExecutionView[]>().map((execution) => execution.id);
}

export const mcpPrincipalScenario: Scenario = {
  name: "공유 profile 의 MCP 요청자",

  async run(context) {
    step("관리자가 GROUP 에이전트를 새 profile 로 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT_CODE,
          name: "MCP shared",
          hermesProfile: MCP_PRINCIPAL_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${MCP_PRINCIPAL_PROFILE}`,
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
          body: { profileName: MCP_PRINCIPAL_PROFILE, label: "mcp-principal-e2e" },
        }),
        200,
        "공유 profile MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      context.hermes.setMemoryReadMcp(context.api.replace(/\/api\/v1$/, "") + "/mcp", issued.token);

      step("아빠와 아이가 각자 제목만 싣는 개인 Memory 를 만든다");
      const dadMemory = expectStatus(
        await call(context, "/memories", {
          method: "POST",
          token: context.tokens.dad,
          body: { scope: "USER", title: "아빠 공유 검사", content: "아빠 공유 검사 본문", alwaysInject: false },
        }),
        200,
        "아빠 Memory 생성",
      ).json<MemoryView>();
      memoryIds.push({ token: context.tokens.dad, id: dadMemory.id });
      const kidMemory = expectStatus(
        await call(context, "/memories", {
          method: "POST",
          token: context.tokens.kid,
          body: { scope: "USER", title: "아이 공유 검사", content: "아이 공유 검사 본문", alwaysInject: false },
        }),
        200,
        "아이 Memory 생성",
      ).json<MemoryView>();
      memoryIds.push({ token: context.tokens.kid, id: kidMemory.id });

      step("두 사용자가 겹쳐 같은 에이전트로 보내면 각자 자기 본문만 읽는다");
      const [dadOwn, kidOwn] = await sendOverlapping(
        context,
        { token: context.tokens.dad, memoryId: dadMemory.id },
        { token: context.tokens.kid, memoryId: kidMemory.id },
      );
      expect(dadOwn.assistantText.includes("아빠 공유 검사 본문") && !dadOwn.assistantText.includes("아이 공유 검사 본문"),
        `아빠의 답이 자기 본문만 담지 않는다: ${dadOwn.assistantText}`);
      expect(kidOwn.assistantText.includes("아이 공유 검사 본문") && !kidOwn.assistantText.includes("아빠 공유 검사 본문"),
        `아이의 답이 자기 본문만 담지 않는다: ${kidOwn.assistantText}`);

      step("상대의 Memory 번호로 보내면 읽을 수 없다고 답하고 상대 본문이 없다");
      const [kidCross, dadCross] = await sendOverlapping(
        context,
        { token: context.tokens.kid, memoryId: dadMemory.id },
        { token: context.tokens.dad, memoryId: kidMemory.id },
      );
      expect(dadCross.assistantText === NOT_READABLE && !dadCross.assistantText.includes("아이 공유 검사 본문"),
        `아빠가 아이의 Memory 를 읽었다: ${dadCross.assistantText}`);
      expect(kidCross.assistantText === NOT_READABLE && !kidCross.assistantText.includes("아빠 공유 검사 본문"),
        `아이가 아빠의 Memory 를 읽었다: ${kidCross.assistantText}`);

      step("사용량 목록에는 자기 turn 의 실행만 보인다");
      const dadTurns = [dadOwn.executionId, dadCross.executionId];
      const kidTurns = [kidOwn.executionId, kidCross.executionId];
      const dadListed = await executionIds(context, context.tokens.dad);
      const kidListed = await executionIds(context, context.tokens.kid);
      expect(dadTurns.every((id) => dadListed.includes(id)), `아빠의 실행이 목록에 없다: ${dadListed.join()}`);
      expect(kidTurns.every((id) => kidListed.includes(id)), `아이의 실행이 목록에 없다: ${kidListed.join()}`);
      expect(kidTurns.every((id) => !dadListed.includes(id)), "아빠의 사용량 목록에 아이 turn 의 실행이 보인다");
      expect(dadTurns.every((id) => !kidListed.includes(id)), "아이의 사용량 목록에 아빠 turn 의 실행이 보인다");
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
          "공유 profile 검사 Memory 정리",
        ));
      }
      const tokenId = issued?.id;
      if (tokenId !== undefined) {
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "공유 profile MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, `/admin/agents/${AGENT_CODE}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: { enabled: false, visibility: "GROUP", ownerEmail: null },
        }),
        200,
        "공유 profile 에이전트 끄기",
      ));
      // finally 에서 던지면 원래 실패가 사라지므로, 원래 실패가 없을 때만 정리 실패를 알린다.
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
