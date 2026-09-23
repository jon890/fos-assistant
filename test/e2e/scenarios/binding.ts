/** 실행할 profile 은 요청자가 고른 에이전트에서만 나온다는 것을 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

/** 이 바인딩의 provider 와 모델이 사용량 시나리오의 환산 금액을 정한다. */
export const DAD_BINDING = {
  // 에이전트 code(`dad`) 와 일부러 다르게 둔다. 둘이 같으면 대시보드에 code 를 넘겨도 검사가 통과한다.
  profileName: "dad-profile",
  provider: "openai-codex",
  model: "gpt-5.6-sol",
} as const;

export const bindingScenario: Scenario = {
  name: "에이전트 등록",

  async run(context) {
    step("없는 에이전트를 고르면 다른 credential 로 돌지 않고 거절된다");
    const refused = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.kid,
        body: { text: "숙제 도와줘", agentCode: "missing" },
      }),
      404,
      "없는 에이전트를 고른 대화",
    );
    expect(
      refused.json<{ code: string }>().code === "AGENT_NOT_FOUND",
      `기대한 오류 코드가 아니다: ${refused.body}`,
    );

    step("admin 이 개인 에이전트를 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: "dad",
          name: "Dad",
          hermesProfile: DAD_BINDING.profileName,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${DAD_BINDING.profileName}`,
          provider: DAD_BINDING.provider,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "PRIVATE",
          ownerEmail: "dad@example.com",
        },
      }),
      200,
      "에이전트 등록",
    );

    step("member 는 admin 전용 엔드포인트를 쓰지 못한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.kid,
        body: {
          code: "kid",
          name: "Kid",
          hermesProfile: "kid",
          apiBaseUrl: "http://127.0.0.1:1/p/kid",
          provider: "x",
          model: "y",
          costMode: "API",
          credentialScope: "DEDICATED",
          visibility: "PRIVATE",
          ownerEmail: "kid@example.com",
        },
      }),
      403,
      "member 의 admin 호출",
    );
  },
};
