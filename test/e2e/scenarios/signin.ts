/**
 * 로그인 허용 판정을 본다.
 *
 * <p>웹 계층은 여기서 띄우지 않으므로 「로그인을 시도한다」 대신 Control Plane 의 판정 경로를 직접
 * 부른다.
 *
 * <p>허용된 주소가 통과하는 것은 여기서 보지 않는다. 허용 목록에 행을 넣는 경로가 아직 없기 때문이다.
 * 사람을 먼저 더하는 시나리오가 그것을 본다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

export const signInScenario: Scenario = {
  name: "로그인 허용 판정",

  async run(context) {
    step("허용 목록에 없는 주소는 들어오지 못한다");
    const rejected = expectStatus(
      await call(context, "/signin/allowed", {
        method: "POST",
        token: context.tokens.signin,
        body: { email: "stranger@example.com" },
      }),
      200,
      "허용 목록에 없는 주소",
    ).json<{ allowed: boolean }>();
    expect(rejected.allowed === false, "허용 목록에 없는 주소가 통과했다");

    step("대화용 토큰으로는 이 경로를 부를 수 없다");
    expectStatus(
      await call(context, "/signin/allowed", {
        method: "POST",
        token: context.tokens.dad,
        body: { email: "stranger@example.com" },
      }),
      401,
      "purpose 가 signin 이 아닌 토큰",
    );
  },
};
