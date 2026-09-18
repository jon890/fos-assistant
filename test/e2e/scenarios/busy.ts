/**
 * 공유 gateway 가 동시 실행 한도에 닿아 429 로 거절했을 때를 본다.
 *
 * <p>공유 gateway 는 한도를 모든 profile 이 나눠 쓴다. 한 사람이 채우면 다른 사람의 대화가 그
 * 자리에서 실패한다. 그것이 Hermes 에 닿지 못한 것과 같은 값으로 적히면, 한도를 올려야 하는지
 * 사용량 기록으로 판단할 수 없다.
 *
 * <p>여기서 다시 보내지 않는 것도 함께 본다. 붐비는데 다시 보내면 더 붐빈다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type Failure = { code: string; message: string };
type Execution = { id: number; status: string; errorCode: string | null };

export const busyScenario: Scenario = {
  name: "동시 실행 한도에 닿은 대화",

  async run(context) {
    const before = context.hermes.submitCount();
    context.hermes.busy();

    try {
      step("붐비는 gateway 가 거절하면 닿지 못한 것과 다른 코드로 답한다");
      const refused = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: "붐빔 검사", agentCode: "dad" },
        }),
        429,
        "붐빌 때 보낸 대화",
      ).json<Failure>();
      expect(
        refused.code === "HERMES_BUSY",
        `붐빈 것을 다른 오류와 구분하지 않았다: ${refused.code}`,
      );

      step("거절당한 뒤 다시 보내지 않는다");
      expect(
        context.hermes.submitCount() - before === 1,
        `429 를 받고 Hermes 를 다시 불렀다: ${context.hermes.submitCount() - before}번`,
      );

      step("붐벼서 실패한 것이 실행 기록에 그 코드로 남는다");
      const executions = expectStatus(
        await call(context, "/usage/executions?limit=10", { token: context.tokens.dad }),
        200,
        "실행 기록",
      ).json<Execution[]>();
      const busy = executions.find((each) => each.errorCode === "HERMES_BUSY");
      expect(busy !== undefined, "붐벼서 실패한 실행이 기록에 남지 않았다");
      expect(
        busy!.status === "FAILED",
        `붐벼서 실패한 실행이 실패로 남지 않았다: ${busy!.status}`,
      );
    } finally {
      context.hermes.clearBusy();
    }

    step("한도가 풀리면 같은 에이전트가 다시 돈다");
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "붐빔 해제 검사", agentCode: "dad" },
      }),
      200,
      "한도가 풀린 뒤의 대화",
    );
  },
};
