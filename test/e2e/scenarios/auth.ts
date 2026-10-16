/** 누가 들어올 수 있고 누가 막히는지를 본다. */
import { call, expectStatus, step, type Scenario } from "../harness.ts";

export const authScenario: Scenario = {
  name: "로그인과 토큰",

  async run(context) {
    step("첫 사용자가 admin 이 되고 다음 사용자는 member 가 된다");
    expectStatus(
      await call(context, "/chat/conversations", { token: context.tokens.dad }),
      200,
      "첫 사용자",
    );
    expectStatus(
      await call(context, "/chat/conversations", { token: context.tokens.kid }),
      200,
      "두 번째 사용자",
    );

    step("위조한 토큰은 통과하지 못한다");
    const forged = `${context.tokens.dad.slice(0, context.tokens.dad.lastIndexOf("."))}.deadbeef`;
    expectStatus(
      await call(context, "/chat/conversations", { token: forged }),
      403,
      "서명을 바꾼 토큰",
    );
  },
};
