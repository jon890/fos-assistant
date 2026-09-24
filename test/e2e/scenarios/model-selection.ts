/**
 * 실행마다 모델을 정해 보내고, 실제로 돈 모델을 적고, 막히면 다음으로 넘기는 것을 본다.
 *
 * <p>이 시나리오는 사용량 합계를 검사하는 시나리오보다 뒤에 돈다. 여기서 실행을 더 만들기 때문이다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { DAD_BINDING } from "./binding.ts";

type ModelOptionView = { rank: number; provider: string; model: string };
type ExecutionView = {
  id: number;
  provider: string | null;
  model: string | null;
  status: string;
  errorCode: string | null;
  retryOfExecutionId: number | null;
};
type Turn = { conversationId: number; executionId: number; assistantText: string };

/** 막힘을 검사할 때 2순위로 쓸 것이다. */
const SECOND = { provider: "nvidia", model: "example-provider/example-model-b" } as const;

export const modelSelectionScenario: Scenario = {
  name: "모델 선택과 넘김",

  async run(context) {
    step("에이전트를 등록하면 1순위가 함께 만들어진다");
    const seeded = expectStatus(
      await call(context, "/admin/agents/dad/models", { token: context.tokens.dad }),
      200,
      "모델 목록 조회",
    ).json<ModelOptionView[]>();
    expect(
      seeded.length === 1 && seeded[0]!.rank === 1
        && seeded[0]!.provider === DAD_BINDING.provider
        && seeded[0]!.model === DAD_BINDING.model,
      `1순위가 만들어지지 않았다: ${JSON.stringify(seeded)}`,
    );

    step("요청에 provider 와 모델을 둘 다 싣는다");
    expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "모델을 싣는지 검사", agentCode: "dad" },
      }),
      200,
      "모델을 실은 대화",
    );
    const sent = context.hermes.lastSubmittedRuntime();
    expect(
      sent.provider === DAD_BINDING.provider && sent.model === DAD_BINDING.model,
      `요청에 provider 와 모델이 함께 실리지 않았다: ${JSON.stringify(sent)}`,
    );

    step("실제로 돈 모델은 세션 조회의 값이다");
    const probe = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "세션 모델 검사", agentCode: "dad" },
      }),
      200,
      "세션 모델 검사",
    ).json<Turn>();
    const afterProbe = await executionsOf(context);
    const probed = afterProbe.find((execution) => execution.id === probe.executionId);
    expect(
      probed?.model === "example-provider/example-model-c" && probed?.provider === "nvidia",
      `실제로 돈 모델을 적지 않았다: ${JSON.stringify(probed)}`,
    );

    step("빈 목록은 거절한다");
    const refused = await call(context, "/admin/agents/dad/models", {
      method: "PUT",
      token: context.tokens.dad,
      body: { models: [] },
    });
    expect(refused.status === 400, `빈 목록이 거절되지 않았다: ${refused.status}`);

    step("목록 전체를 바꾸면 그 순서로 남는다");
    const replaced = expectStatus(
      await call(context, "/admin/agents/dad/models", {
        method: "PUT",
        token: context.tokens.dad,
        body: {
          models: [
            { provider: DAD_BINDING.provider, model: DAD_BINDING.model },
            { provider: SECOND.provider, model: SECOND.model },
          ],
        },
      }),
      200,
      "모델 목록 저장",
    ).json<ModelOptionView[]>();
    expect(
      replaced.length === 2 && replaced[1]!.rank === 2 && replaced[1]!.provider === SECOND.provider,
      `순서대로 남지 않았다: ${JSON.stringify(replaced)}`,
    );

    step("1순위가 막히면 그 턴 안에서 2순위로 넘어가 답이 온다");
    context.hermes.blockProvider(DAD_BINDING.provider);
    try {
      const switched = expectStatus(
        await call(context, "/chat/messages", {
          method: "POST",
          token: context.tokens.dad,
          body: { text: "넘김 검사", agentCode: "dad" },
        }),
        200,
        "넘어간 대화",
      ).json<Turn>();
      expect(
        switched.assistantText.includes("넘김 검사"),
        `넘어간 뒤의 답이 오지 않았다: ${switched.assistantText}`,
      );
      const after = context.hermes.lastSubmittedRuntime();
      expect(
        after.provider === SECOND.provider && after.model === SECOND.model,
        `2순위로 다시 보내지 않았다: ${JSON.stringify(after)}`,
      );

      step("그 대화의 실행이 둘 남고 하나는 실패, 하나는 성공이다");
      const executions = await executionsOf(context);
      const succeeded = executions.find((execution) => execution.id === switched.executionId);
      expect(succeeded?.status === "SUCCEEDED", `성공한 실행이 없다: ${JSON.stringify(succeeded)}`);
      const failed = executions.find(
        (execution) => execution.id === succeeded?.retryOfExecutionId,
      );
      expect(
        failed?.status === "FAILED" && failed?.errorCode === "PROVIDER_BLOCKED",
        `막혀서 실패한 실행이 남지 않았다: ${JSON.stringify(failed)}`,
      );

      step("막힌 provider 가 관리 화면에 보인다");
      const blocked = expectStatus(
        await call(context, "/admin/providers/blocked", { token: context.tokens.dad }),
        200,
        "막힌 provider 조회",
      ).json<{ provider: string; remainingSeconds: number }[]>();
      expect(
        blocked.some((row) => row.provider === DAD_BINDING.provider && row.remainingSeconds > 0),
        `막힌 provider 가 보이지 않는다: ${JSON.stringify(blocked)}`,
      );
    } finally {
      context.hermes.clearBlockedProviders();
    }

    step("목록을 1순위 하나로 되돌린다");
    expectStatus(
      await call(context, "/admin/agents/dad/models", {
        method: "PUT",
        token: context.tokens.dad,
        body: { models: [{ provider: DAD_BINDING.provider, model: DAD_BINDING.model }] },
      }),
      200,
      "모델 목록 복원",
    );
  },
};

async function executionsOf(context: Parameters<Scenario["run"]>[0]): Promise<ExecutionView[]> {
  return expectStatus(
    await call(context, "/usage/executions?limit=50", { token: context.tokens.dad }),
    200,
    "사용량 조회",
  ).json<ExecutionView[]>();
}
