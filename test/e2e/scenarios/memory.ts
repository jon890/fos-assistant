/** Memory 의 공개 범위와 관리 권한을 검사한다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type MemoryView = {
  id: number;
  title: string;
  content: string;
  scope: string;
  alwaysInject: boolean;
  status: string;
  omittedFromContext: boolean;
};

type AgentToken = { token: string };

/** 사용량 시나리오보다 먼저 Memory 권한과 주입 확인을 위해 실행하는 대화 수다. */
export const MEMORY_CONTEXT_TURNS = 2;

export const memoryScenario: Scenario = {
  name: "Memory 공개 범위",

  async run(context) {
    step("한 사람의 개인 항목은 다른 구성원의 목록에 없다");
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
      "다른 구성원의 개인 Memory 가 목록에 보인다",
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

    step("member 는 가족 항목을 생성, 수정, 삭제하지 못한다");
    const family = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "FAMILY", title: "가족", content: "가족 내용", alwaysInject: false },
      }),
      200,
      "가족 Memory 생성",
    ).json<MemoryView>();
    expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.kid,
        body: { scope: "FAMILY", title: "허용 안 됨", content: "내용", alwaysInject: false },
      }),
      403,
      "member 가족 Memory 생성",
    );
    expectStatus(
      await call(context, `/memories/${family.id}`, {
        method: "PATCH",
        token: context.tokens.kid,
        body: { content: "허용 안 됨", alwaysInject: true },
      }),
      403,
      "member 가족 Memory 수정",
    );
    expectStatus(
      await call(context, `/memories/${family.id}`, {
        method: "DELETE",
        token: context.tokens.kid,
      }),
      403,
      "member 가족 Memory 삭제",
    );

    step("다른 구성원의 개인 항목은 Hermes 요청 instructions 에 들어가지 않는다");
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
      "다른 구성원의 개인 Memory 가 Hermes 요청에 들어갔다",
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
      await call(context, `/memories/${family.id}`, { method: "DELETE", token: context.tokens.dad }),
      200,
      "가족 Memory 정리",
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

    step("MCP 토큰은 발급된 사용자만 정하고 다른 사람의 본문은 읽지 못한다");
    const dadOnly = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.dad,
        body: { scope: "USER", title: "MCP 개인", content: "MCP 에서도 숨겨야 하는 내용" },
      }),
      200,
      "MCP 대상 Memory 생성",
    ).json<MemoryView>();
    const kidToken = expectStatus(
      await call(context, "/admin/agent-tokens", {
        method: "POST",
        token: context.tokens.dad,
        body: { userEmail: "kid@example.com", label: "e2e-kid" },
      }),
      200,
      "아이 MCP 토큰 발급",
    ).json<AgentToken>();
    const dadToken = expectStatus(
      await call(context, "/admin/agent-tokens", {
        method: "POST",
        token: context.tokens.dad,
        body: { userEmail: "dad@example.com", label: "e2e-dad" },
      }),
      200,
      "아빠 MCP 토큰 발급",
    ).json<AgentToken>();
    const kidOwn = expectStatus(
      await call(context, "/memories", {
        method: "POST",
        token: context.tokens.kid,
        body: { scope: "USER", title: "MCP 본인", content: "아이 MCP 본문" },
      }),
      200,
      "MCP 본인 Memory 생성",
    ).json<MemoryView>();
    const kidReadsDad = await fetch(context.api.replace("/api/v1", "/mcp"), {
      method: "POST",
      headers: { Authorization: `Bearer ${kidToken.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "memory_read", arguments: { id: dadOnly.id, user_id: 1 } } }),
    });
    const kidReadsDadBody = await kidReadsDad.text();
    expect(kidReadsDad.status === 200, "아이 MCP 요청이 처리되지 않았다");
    expect(!kidReadsDadBody.includes(dadOnly.content), "아이 토큰에 아빠 Memory 본문이 있다");
    const kidReadsOwn = await fetch(context.api.replace("/api/v1", "/mcp"), {
      method: "POST",
      headers: { Authorization: `Bearer ${kidToken.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "memory_read", arguments: { id: kidOwn.id } } }),
    });
    const kidReadsOwnBody = await kidReadsOwn.text();
    expect(kidReadsOwn.status === 200 && kidReadsOwnBody.includes(kidOwn.content), "아이 토큰의 본문이 오지 않는다");
    const dadReadsKid = await fetch(context.api.replace("/api/v1", "/mcp"), {
      method: "POST",
      headers: { Authorization: `Bearer ${dadToken.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 3, method: "tools/call", params: { name: "memory_read", arguments: { id: kidOwn.id, user_id: 2 } } }),
    });
    const dadReadsKidBody = await dadReadsKid.text();
    expect(dadReadsKid.status === 200, "아빠 MCP 요청이 처리되지 않았다");
    expect(!dadReadsKidBody.includes(kidOwn.content), "아빠 토큰에 아이 Memory 본문이 있다");
    const dadReadsOwn = await fetch(context.api.replace("/api/v1", "/mcp"), {
      method: "POST",
      headers: { Authorization: `Bearer ${dadToken.token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "memory_read", arguments: { id: dadOnly.id } } }),
    });
    const dadReadsOwnBody = await dadReadsOwn.text();
    expect(dadReadsOwn.status === 200 && dadReadsOwnBody.includes(dadOnly.content), "아빠 토큰의 본문이 오지 않는다");
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
