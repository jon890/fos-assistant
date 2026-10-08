/** Memory 의 공개 범위와 관리 권한을 검사한다. */
import { randomUUID } from "node:crypto";
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { signedCallContext } from "../mcp-context.ts";
import { AGENT_TOOLS_PROFILE } from "./agent-tools.ts";
import { DAD_BINDING } from "./binding.ts";

type MemoryView = {
  id: number;
  title: string;
  content: string;
  scope: string;
  alwaysInject: boolean;
  status: string;
  sensitive: boolean;
  omittedFromContext: boolean;
};

type AgentToken = { id: number; token: string };

/** 요청자를 정하지 못한 MCP 호출이 받는 도구 결과 문구다. 이유를 가리지 않고 같다. */
const INVALID_CALL_CONTEXT = "호출 맥락을 확인할 수 없습니다. 새 대화에서 다시 시도해 주세요.";

/** 사용량 시나리오보다 먼저 Memory 권한과 주입 확인을 위해 실행하는 대화 수다. */
export const MEMORY_CONTEXT_TURNS = 3;

export const memoryScenario: Scenario = {
  name: "Memory 공개 범위",

  async run(context) {
    step("한 사람의 개인 항목은 다른 사용자의 목록에 없다");
    const personal = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "개인", content: "아빠만 보는 내용" },
      }),
      200,
      "개인 Memory 생성",
    ).json<MemoryView>();
    const memberMemories = expectStatus(
      await call(context, "/memories", { token: context.tokens.kid }),
      200,
      "member Memory 목록",
    ).json<MemoryView[]>();
    expect(
      memberMemories.every((memory) => memory.id !== personal.id),
      "다른 사용자의 개인 Memory 가 목록에 보인다",
    );

    step("생성, 수정, 삭제가 정상 동작한다");
    const updated = expectStatus(
      await call(context, `/memories/${personal.id}`, {
        method: "PATCH",
        token: context.tokens.dad,
        body: { content: "수정한 내용", alwaysInject: true },
      }),
      200,
      "개인 Memory 수정",
    ).json<MemoryView>();
    expect(
      updated.content === "수정한 내용" && updated.alwaysInject,
      "수정한 Memory 내용이나 주입 설정이 다르다",
    );
    expectStatus(
      await call(context, `/memories/${personal.id}`, {
        method: "DELETE",
        token: context.tokens.dad,
      }),
      200,
      "개인 Memory 삭제",
    );

    step("member 는 그룹 항목을 생성, 수정, 삭제하지 못한다");
    const group = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "GROUP", title: "그룹", content: "그룹 내용", alwaysInject: false },
      }),
      200,
      "그룹 Memory 생성",
    ).json<MemoryView>();
    expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.kid,
        body: { scope: "GROUP", title: "허용 안 됨", content: "내용", alwaysInject: false },
      }),
      403,
      "member 그룹 Memory 생성",
    );
    expectStatus(
      await call(context, `/memories/${group.id}`, {
        method: "PATCH",
        token: context.tokens.kid,
        body: { content: "허용 안 됨", alwaysInject: true },
      }),
      403,
      "member 그룹 Memory 수정",
    );
    expectStatus(
      await call(context, `/memories/${group.id}`, {
        method: "DELETE",
        token: context.tokens.kid,
      }),
      403,
      "member 그룹 Memory 삭제",
    );

    step("다른 사용자의 개인 항목은 Hermes 요청 instructions 에 들어가지 않는다");
    const dadMemory = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "아빠 선호", content: "아빠만 아는 내용", alwaysInject: true },
      }),
      200,
      "아빠 개인 Memory 생성",
    ).json<MemoryView>();
    const kidMemory = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.kid,
        body: { scope: "USER", title: "아이 비밀", content: "아이만 아는 비밀 내용", alwaysInject: true },
      }),
      200,
      "아이 개인 Memory 생성",
    ).json<MemoryView>();
    expect(dadMemory.id !== kidMemory.id, "개인 Memory 식별자가 겹친다");
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "Memory 권한 검사", agentCode: "dad" },
      }),
      200,
      "아빠 대화",
    );
    const instructions = context.hermes.lastSubmittedInstructions();
    expect(instructions !== undefined, "Hermes 요청에 instructions 가 없다");
    expect(instructions.includes(dadMemory.content), "내 개인 Memory 가 Hermes 요청에 없다");
    expect(
      !instructions.includes(kidMemory.content),
      "다른 사용자의 개인 Memory 가 Hermes 요청에 들어갔다",
    );
    expectStatus(
      await call(context, `/memories/${dadMemory.id}`, { method: "DELETE", token: context.tokens.dad }),
      200,
      "아빠 개인 Memory 정리",
    );
    expectStatus(
      await call(context, `/memories/${kidMemory.id}`, { method: "DELETE", token: context.tokens.kid }),
      200,
      "아이 개인 Memory 정리",
    );
    expectStatus(
      await call(context, `/memories/${group.id}`, { method: "DELETE", token: context.tokens.dad }),
      200,
      "그룹 Memory 정리",
    );

    step("본문이 상한을 넘는 항목이 있어도 나머지와 색인이 instructions 에 들어간다");
    const tooLong = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "너무 긴 항목", content: "가".repeat(9_000), alwaysInject: true },
      }),
      200,
      "상한을 넘는 Memory 생성",
    ).json<MemoryView>();
    const alsoInjected = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "짧은 항목", content: "짧게 남긴 사실", alwaysInject: true },
      }),
      200,
      "짧은 Memory 생성",
    ).json<MemoryView>();
    const indexedOnly = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "색인만 하는 제목", content: "색인 본문", alwaysInject: false },
      }),
      200,
      "색인 Memory 생성",
    ).json<MemoryView>();
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "긴 Memory 주입 검사", agentCode: "dad" },
      }),
      200,
      "긴 Memory 가 있는 대화",
    );
    const withLongMemory = context.hermes.lastSubmittedInstructions();
    expect(withLongMemory !== undefined, "긴 Memory 가 있는 요청에 instructions 가 없다");
    expect(
      !withLongMemory.includes(tooLong.content),
      "상한을 넘는 Memory 본문이 instructions 에 들어갔다",
    );
    expect(
      withLongMemory.includes(alsoInjected.content),
      "상한을 넘는 항목 때문에 다른 항목까지 빠졌다",
    );
    expect(
      withLongMemory.includes(`[${indexedOnly.id}] ${indexedOnly.title}`),
      "상한을 넘는 항목 때문에 색인이 통째로 빠졌다",
    );
    const omittedList = expectStatus(
      await call(context, "/memories", { token: context.tokens.dad }),
      200,
      "빠진 항목 표시 확인",
    ).json<MemoryView[]>();
    expect(
      omittedList.filter((memory) => memory.omittedFromContext).map((memory) => memory.id).join() ===
        String(tooLong.id),
      "빠진 항목 표시가 상한을 넘는 항목 하나에만 붙지 않았다",
    );
    for (const memory of [tooLong, alsoInjected, indexedOnly]) {
      expectStatus(
        await call(context, `/memories/${memory.id}`, { method: "DELETE", token: context.tokens.dad }),
        200,
        "긴 Memory 검사 정리",
      );
    }

    step("같은 사용자와 같은 에이전트가 받는 Memory 구역은 항상 층 본문과 색인이 정해진 모양 그대로다");
    // Memory 표를 넓히기 전의 코드가 낸 글과 글자 하나까지 같아야 한다. 표를 넓힌 뒤에도 이 검사가 그대로 통과한다.
    const groupAlways = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "GROUP", title: "그룹 사실", content: "우리 그룹은 주말에 장을 본다", alwaysInject: true },
      }),
      200,
      "항상 싣는 그룹 Memory 생성",
    ).json<MemoryView>();
    const userAlways = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "개인 사실", content: "아빠는 국수를 맵지 않게 먹는다", alwaysInject: true },
      }),
      200,
      "항상 싣는 개인 Memory 생성",
    ).json<MemoryView>();
    const userIndexed = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        // 본문이 개인 사실 구역의 상한(200자)을 넘어 색인에만 실린다
        body: { scope: "USER", title: "지원 이력", content: `색인으로만 실리는 본문 ${"가".repeat(200)}`, alwaysInject: false },
      }),
      200,
      "색인에만 싣는 개인 Memory 생성",
    ).json<MemoryView>();
    const userFact = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "딸 이름", content: "딸 이름은 홍지수다", alwaysInject: false },
      }),
      200,
      "개인 사실 구역에 싣는 개인 Memory 생성",
    ).json<MemoryView>();
    const groupIndexed = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "GROUP", title: "그룹 일정", content: "색인으로만 실리는 그룹 본문", alwaysInject: false },
      }),
      200,
      "색인에만 싣는 그룹 Memory 생성",
    ).json<MemoryView>();
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "Memory 구역 모양 검사", agentCode: "dad" },
      }),
      200,
      "Memory 구역 모양을 보는 대화",
    );
    const expectedMemorySection = [
      "# 우리 그룹이 함께 아는 것",
      `- ${groupAlways.content}`,
      "# 지금 묻는 사람에 대해 아는 것",
      `- ${userAlways.content}`,
      "# 지금 묻는 사람에 대해 기억한 것",
      "아래는 이 사람에 대해 기억한 짧은 사실이다. 필요하면 번호로 memory_read 를 불러 다시 읽는다.",
      `- [${userFact.id}] ${userFact.title}: ${userFact.content}`,
      "# 더 물어볼 수 있는 것",
      "아래는 제목만 적은 것이다. 필요하면 memory_read 도구로 본문을 읽는다.",
      `- [${userIndexed.id}] ${userIndexed.title}`,
      `- [${groupIndexed.id}] ${groupIndexed.title}`,
    ].join("\n\n");
    const shaped = context.hermes.lastSubmittedInstructions();
    expect(shaped !== undefined, "Memory 구역 모양을 보는 요청에 instructions 가 없다");
    expect(
      shaped.includes(expectedMemorySection),
      `Memory 구역이 정해진 모양과 다르다:\n${shaped}`,
    );
    expect(
      !shaped.includes(userIndexed.content) && !shaped.includes(groupIndexed.content),
      "색인에만 싣는 항목의 본문이 instructions 에 들어갔다",
    );
    for (const memory of [groupAlways, userAlways, userIndexed, userFact, groupIndexed]) {
      expectStatus(
        await call(context, `/memories/${memory.id}`, { method: "DELETE", token: context.tokens.dad }),
        200,
        "Memory 구역 모양 검사 정리",
      );
    }

    step("profile 토큰만으로는 누구의 본문도 읽지 못한다");
    const dadOnly = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "MCP 개인", content: "MCP 에서도 숨겨야 하는 내용" },
      }),
      200,
      "MCP 대상 Memory 생성",
    ).json<MemoryView>();
    const kidOwn = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.kid,
        body: { scope: "USER", title: "MCP 본인", content: "아이 MCP 본문" },
      }),
      200,
      "MCP 본인 Memory 생성",
    ).json<MemoryView>();
    // 토큰은 profile 만 증명한다. 요청자는 서명한 루트 session 으로 찾은 도는 실행의 사용자라,
    // 서명이 없거나 도는 실행이 없는 루트로 서명한 호출은 누구의 권한으로도 돌지 않는다.
    for (const [who, profileName] of [["아이", AGENT_TOOLS_PROFILE], ["아빠", DAD_BINDING.profileName]] as const) {
      const issued = expectStatus(
        await call(context, "/admin/agent-tokens", {
          method: "POST",
          token: context.tokens.dad,
          body: { profileName, label: `e2e-${profileName}` },
        }),
        200,
        `${who} profile MCP 토큰 발급`,
      ).json<AgentToken>();
      try {
        const idleRoot = `fos-${randomUUID()}`;
        const attempts = [
          ["서명 없음", undefined],
          ["도는 실행이 없는 루트", signedCallContext(issued.token, "memory_read", idleRoot, idleRoot, `call_${randomUUID()}`)],
        ] as const;
        for (const [attempt, _fos_ctx] of attempts) {
          for (const memory of [dadOnly, kidOwn]) {
            const response = await fetch(context.api.replace("/api/v1", "/mcp"), {
              method: "POST",
              headers: { Authorization: `Bearer ${issued.token}`, "Content-Type": "application/json" },
              body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "memory_read", arguments: { id: memory.id, user_id: 1, _fos_ctx } } }),
            });
            const text = await response.text();
            expect(response.status === 200, `${who} profile 토큰의 MCP 요청이 처리되지 않았다(${attempt}): ${response.status}`);
            const body = JSON.parse(text) as { result?: { isError?: boolean; content?: { text?: string }[] } };
            expect(body.result?.isError === true && body.result.content?.[0]?.text === INVALID_CALL_CONTEXT,
              `${who} profile 토큰이 호출 맥락 오류로 거절되지 않았다(${attempt}): ${text}`);
            expect(!text.includes(dadOnly.content) && !text.includes(kidOwn.content),
              `${who} profile 토큰만으로 Memory 본문이 나왔다(${attempt})`);
          }
        }
      } finally {
        expectStatus(await call(context, `/admin/agent-tokens/${issued.id}`, { method: "DELETE", token: context.tokens.dad }),
          200,
          `${who} profile MCP 토큰 폐기`);
      }
    }
    expectStatus(
      await call(context, `/memories/${dadOnly.id}`, { method: "DELETE", token: context.tokens.dad }),
      200,
      "MCP 대상 Memory 정리",
    );
    expectStatus(
      await call(context, `/memories/${kidOwn.id}`, { method: "DELETE", token: context.tokens.kid }),
      200,
      "MCP 본인 Memory 정리",
    );
  },
};
