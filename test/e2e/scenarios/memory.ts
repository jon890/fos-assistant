/** Memory 의 공개 범위와 관리 권한을 검사한다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type MemoryView = {
  id: number;
  title: string;
  content: string;
  scope: string;
  alwaysInject: boolean;
  status: string;
};

/** 사용량 시나리오보다 먼저 Memory 권한 확인을 위해 실행하는 대화 수다. */
export const MEMORY_CONTEXT_TURNS = 1;

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
  },
};
