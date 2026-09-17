/** 실행할 profile 은 요청자의 바인딩에서만 나온다는 것을 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

/** 이 바인딩의 provider 와 모델이 사용량 시나리오의 환산 금액을 정한다. */
export const DAD_BINDING = {
  profileName: "dad",
  provider: "openai-codex",
  model: "gpt-5.5",
} as const;

export const bindingScenario: Scenario = {
  name: "profile 바인딩",

  async run(context) {
    step("바인딩이 없는 사용자는 남의 credential 로 돌지 않고 거절된다");
    const refused = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.kid,
        body: { text: "숙제 도와줘" },
      }),
      409,
      "바인딩 없는 사용자의 대화",
    );
    expect(
      refused.json<{ code: string }>().code === "HERMES_BINDING_MISSING",
      `기대한 오류 코드가 아니다: ${refused.body}`,
    );

    step("admin 이 dad 에게 profile 을 연결한다");
    expectStatus(
      await call(context, "/admin/hermes-bindings", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          email: "dad@example.com",
          profileName: DAD_BINDING.profileName,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${DAD_BINDING.profileName}`,
          provider: DAD_BINDING.provider,
          model: DAD_BINDING.model,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
        },
      }),
      200,
      "바인딩 생성",
    );

    step("member 는 admin 전용 엔드포인트를 쓰지 못한다");
    expectStatus(
      await call(context, "/admin/hermes-bindings", {
        method: "POST",
        token: context.tokens.kid,
        body: {
          email: "kid@example.com",
          profileName: "kid",
          apiBaseUrl: "http://127.0.0.1:1/p/kid",
          provider: "x",
          model: "y",
          costMode: "API",
          credentialScope: "DEDICATED",
        },
      }),
      403,
      "member 의 admin 호출",
    );
  },
};
