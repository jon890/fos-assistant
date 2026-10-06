/**
 * 에이전트가 대화 중에 `memory_remember` 로 사실을 남길 때, 사람이 말한 turn 의 근거 있는 호출만 바로 저장되고
 * 그 밖은 제안으로 남는 것을 실제 대화 turn 으로 본다.
 *
 * <p>계약은 ADR-091 과 `docs/backend/memory.md` 의 「에이전트가 기억을 남기는 길」 이 갖는다. 주인과 대화는 서명한
 * `_fos_ctx` 로 찾은 origin 실행에서 정한다. 가짜 Hermes 가 run 마다 그 run 의 session 으로 서명해 부른다.
 */
import { randomUUID } from "node:crypto";
import { call, expect, expectStatus, fail, step, type Context, type Scenario } from "../harness.ts";
import { signedCallContext } from "../mcp-context.ts";

export const MEMORY_REMEMBER_MCP_PROFILE = "memory-remember-mcp";

const AGENT_CODE = "memory-remember-mcp";
const REMEMBERED = "기억했다";
const PROPOSED = "제안으로 남겼다";
/** 요청자를 정하지 못한 MCP 호출이 받는 도구 결과 문구다. */
const INVALID_CALL_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";

const DIRECT_TEXT = "다른 사람은 홍길동이야. 기억해 줘";
const DIRECT_TITLE = "기억 검사 별칭 홍길동";
const UNQUOTED_TEXT = "우리 집 강아지에 대한 이야기를 하나 할게";
const UNQUOTED_TITLE = "기억 검사 강아지 이름";
const OUTSIDE_SEARCH_TEXT = "기억 검사 날씨 찾아 줘";
const OUTSIDE_TEXT = "고모 생일은 3월 5일이야";
const OUTSIDE_TITLE = "기억 검사 고모 생일";

type Turn = { conversationId: string; executionId: number; assistantText: string };
type MemoryView = { id: number; title: string; content: string; status: string };
type Capture = { id: number; memoryId: number; kind: string; status: string; title: string };

async function send(context: Context, text: string, conversationId?: string): Promise<Turn> {
  return expectStatus(
    await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: conversationId === undefined ? { text, agentCode: AGENT_CODE } : { conversationId, text, agentCode: AGENT_CODE },
    }),
    200,
    `기억 검사 대화(${text})`,
  ).json<Turn>();
}

async function memories(context: Context): Promise<MemoryView[]> {
  return expectStatus(await call(context, "/memories", { token: context.tokens.dad }), 200, "Memory 목록").json<MemoryView[]>();
}

async function captures(context: Context, conversationId: string): Promise<Capture[]> {
  return expectStatus(
    await call(context, `/chat/conversations/${conversationId}/memory-captures`, { token: context.tokens.dad }),
    200,
    "기억 기록 목록",
  ).json<Capture[]>();
}

function indexLine(memory: { id: number; title: string }): string {
  return `- [${memory.id}] ${memory.title}`;
}

export const memoryRememberMcpScenario: Scenario = {
  name: "기억 남기기 도구",

  async run(context) {
    step("관리자가 GROUP 에이전트를 새 profile 로 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: AGENT_CODE,
          name: "Memory remember MCP",
          hermesProfile: MEMORY_REMEMBER_MCP_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${MEMORY_REMEMBER_MCP_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "GROUP",
          ownerEmail: null,
        },
      }),
      200,
      "기억 남기기 에이전트 등록",
    );

    let issued: { id: number; token: string } | undefined;
    let failed = false;
    const createdMemoryIds = new Set<number>();
    try {
      step("그 profile 에 묶은 토큰을 발급해 가짜 Hermes 에 준다");
      issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName: MEMORY_REMEMBER_MCP_PROFILE, label: "memory-remember-mcp-e2e" },
        }),
        200,
        "기억 남기기 MCP 토큰 발급",
      ).json<{ id: number; token: string }>();
      const endpoint = context.api.replace(/\/api\/v1$/, "") + "/mcp";
      context.hermes.setMemoryReadMcp(endpoint, issued.token);

      step("서명 없는 호출은 거절된다");
      const unsigned = await fetch(endpoint, {
        method: "POST",
        headers: { Authorization: `Bearer ${issued.token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          jsonrpc: "2.0", id: 1, method: "tools/call",
          params: { name: "memory_remember", arguments: { title: "서명 없음", content: "서명 없는 호출", evidence: "서명 없는 호출" } },
        }),
      });
      const unsignedBody = await unsigned.json() as { result?: { isError?: boolean; content?: { text?: string }[] } };
      expect(unsignedBody.result?.isError === true && unsignedBody.result.content?.[0]?.text === INVALID_CALL_CONTEXT,
        `서명 없는 호출이 거절되지 않았다: ${JSON.stringify(unsignedBody)}`);
      const idleRoot = `fos-${randomUUID()}`;
      const idle = await fetch(endpoint, {
        method: "POST",
        headers: { Authorization: `Bearer ${issued.token}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          jsonrpc: "2.0", id: 1, method: "tools/call",
          params: { name: "memory_remember", arguments: {
            title: "도는 실행 없음", content: "도는 실행이 없는 루트", evidence: "도는 실행이 없는 루트",
            _fos_ctx: signedCallContext(issued.token, "memory_remember", idleRoot, idleRoot, `call_${randomUUID()}`),
          } },
        }),
      });
      const idleBody = await idle.json() as { result?: { isError?: boolean; content?: { text?: string }[] } };
      expect(idleBody.result?.isError === true && idleBody.result.content?.[0]?.text === INVALID_CALL_CONTEXT,
        `도는 실행이 없는 루트의 호출이 거절되지 않았다: ${JSON.stringify(idleBody)}`);
      expect((await memories(context)).every((m) => m.title !== "서명 없음" && m.title !== "도는 실행 없음"),
        "거절한 호출이 Memory 를 만들었다");

      step("사용자가 말한 사실을 근거와 함께 남기면 바로 저장하고 CREATED 기록이 생긴다");
      context.hermes.setMemoryRememberCall(DIRECT_TEXT,
        { title: DIRECT_TITLE, content: "다른 사람은 홍길동이다", evidence: "다른 사람은 홍길동이야" });
      const direct = await send(context, DIRECT_TEXT);
      expect(direct.assistantText.startsWith(REMEMBERED), `바로 저장의 답이 다르다: ${direct.assistantText}`);
      const saved = (await memories(context)).filter((m) => m.title === DIRECT_TITLE);
      expect(saved.length === 1, `저장한 Memory 가 한 줄이 아니다: ${JSON.stringify(saved)}`);
      const savedMemory = saved[0]!;
      createdMemoryIds.add(savedMemory.id);
      expect(savedMemory.status === "ACCEPTED", `바로 저장한 Memory 의 상태가 다르다: ${savedMemory.status}`);
      const directCaptures = (await captures(context, direct.conversationId)).filter((c) => c.memoryId === savedMemory.id);
      expect(directCaptures.length === 1 && directCaptures[0]!.kind === "CREATED",
        `CREATED 기록이 아니다: ${JSON.stringify(directCaptures)}`);
      const directCapture = directCaptures[0]!;

      step("다음 turn 의 instructions 에 기억 지침과 저장한 제목의 색인이 실린다");
      await send(context, "색인 확인용 안부", direct.conversationId);
      const instructions = context.hermes.lastSubmittedInstructions();
      expect(instructions !== undefined, "다음 turn 의 요청에 instructions 가 없다");
      expect(instructions.includes("# 기억"), `공통 지침에 「# 기억」 절이 없다:\n${instructions}`);
      expect(instructions.includes("# 더 물어볼 수 있는 것"), `색인 절이 없다:\n${instructions}`);
      expect(instructions.includes(indexLine(savedMemory)), `색인에 저장한 제목이 없다:\n${instructions}`);

      step("근거 인용이 질문에 없으면 제안으로 남고 받아들이면 색인에 실린다");
      context.hermes.setMemoryRememberCall(UNQUOTED_TEXT,
        { title: UNQUOTED_TITLE, content: "강아지 이름은 콩이다", evidence: "강아지 이름은 콩이야" });
      const unquoted = await send(context, UNQUOTED_TEXT, direct.conversationId);
      expect(unquoted.assistantText.startsWith(PROPOSED), `근거 없는 호출의 답이 다르다: ${unquoted.assistantText}`);
      const proposed = (await memories(context)).filter((m) => m.title === UNQUOTED_TITLE);
      expect(proposed.length === 1 && proposed[0]!.status === "PROPOSED", `제안이 아니다: ${JSON.stringify(proposed)}`);
      const proposedMemory = proposed[0]!;
      createdMemoryIds.add(proposedMemory.id);
      const proposedCaptures = (await captures(context, direct.conversationId)).filter((c) => c.memoryId === proposedMemory.id);
      expect(proposedCaptures.length === 1 && proposedCaptures[0]!.kind === "PROPOSED",
        `PROPOSED 기록이 아니다: ${JSON.stringify(proposedCaptures)}`);
      await send(context, "제안 승인 전 확인", direct.conversationId);
      expect(!(context.hermes.lastSubmittedInstructions() ?? "").includes(indexLine(proposedMemory)),
        "승인 전 제안이 색인에 실렸다");
      expectStatus(
        await call(context, `/memories/${proposedMemory.id}/accept`, { method: "POST", token: context.tokens.dad }),
        200,
        "제안 받아들이기",
      );
      await send(context, "제안 승인 뒤 확인", direct.conversationId);
      expect((context.hermes.lastSubmittedInstructions() ?? "").includes(indexLine(proposedMemory)),
        "받아들인 제안이 색인에 실리지 않았다");

      step("같은 대화의 앞 turn 이 바깥 도구를 썼으면 근거가 있어도 제안으로 내려간다");
      context.hermes.setOutsideToolRun(OUTSIDE_SEARCH_TEXT);
      const searched = await send(context, OUTSIDE_SEARCH_TEXT);
      context.hermes.setMemoryRememberCall(OUTSIDE_TEXT,
        { title: OUTSIDE_TITLE, content: "고모 생일은 3월 5일이다", evidence: "고모 생일은 3월 5일이야" });
      const outside = await send(context, OUTSIDE_TEXT, searched.conversationId);
      expect(outside.assistantText.startsWith(PROPOSED), `바깥 도구 뒤 호출의 답이 다르다: ${outside.assistantText}`);
      const outsideSaved = (await memories(context)).filter((m) => m.title === OUTSIDE_TITLE);
      expect(outsideSaved.length === 1 && outsideSaved[0]!.status === "PROPOSED",
        `바깥 도구 뒤 호출이 제안이 아니다: ${JSON.stringify(outsideSaved)}`);
      createdMemoryIds.add(outsideSaved[0]!.id);

      step("CREATED 기록을 되돌리면 Memory 목록에서 사라진다");
      expectStatus(
        await call(context, `/memory-captures/${directCapture.id}/undo`, { method: "POST", token: context.tokens.dad }),
        200,
        "기록 되돌리기",
      );
      expect((await memories(context)).every((m) => m.id !== savedMemory.id), "되돌린 Memory 가 목록에 남았다");
      expect((await captures(context, direct.conversationId)).every((c) => c.id !== directCapture.id),
        "되돌린 기록이 기록 목록에 남았다");
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
      // 뒤 시나리오의 Memory 주입과 색인에 걸리지 않도록 이 시나리오가 만든 항목을 지운다. 되돌려 이미 없는 항목은 건너뛴다.
      for (const id of createdMemoryIds) {
        await cleanup(async () => {
          const response = await call(context, `/memories/${id}`, { method: "DELETE", token: context.tokens.dad });
          if (response.status !== 200 && response.status !== 404) fail(`기억 검사 Memory ${id} 삭제 실패: ${response.status}`);
        });
      }
      const tokenId = issued?.id;
      if (tokenId !== undefined) {
        await cleanup(async () => expectStatus(
          await call(context, `/admin/agent-tokens/${tokenId}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          "기억 남기기 MCP 토큰 폐기",
        ));
      }
      await cleanup(async () => expectStatus(
        await call(context, `/admin/agents/${AGENT_CODE}`, {
          method: "PATCH",
          token: context.tokens.dad,
          body: { enabled: false, visibility: "GROUP", ownerEmail: null },
        }),
        200,
        "기억 남기기 에이전트 끄기",
      ));
      if (!failed && cleanupErrors.length > 0) {
        fail(cleanupErrors.map((error) => (error instanceof Error ? error.message : String(error))).join("; "));
      }
    }
  },
};
