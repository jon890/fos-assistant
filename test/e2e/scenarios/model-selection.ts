/**
 * 실행이 대화가 고른 모델과 effort 로 Hermes 를 부르고, 실제로 돈 모델을 적고, 막혀도 넘기지 않는 것을 본다.
 *
 * <p>이 시나리오는 사용량 합계를 검사하는 시나리오보다 뒤에 돈다. 여기서 실행을 더 만들기 때문이다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type ExecutionView = {
  id: number;
  provider: string | null;
  model: string | null;
  status: string;
  errorCode: string | null;
};
type Turn = { conversationId: string; executionId: number; assistantText: string };

/** 가짜 Hermes 의 세션이 모델을 받지 않았을 때 답하는 기본값이다. */
const PROFILE_DEFAULT = { provider: "openai-codex", model: "example-model" } as const;

/** 대화에서 고를 모델이다. 기본값과 달라야 고른 값이 실렸는지 알 수 있다. */
const CHOSEN = { provider: "nvidia", model: "example-provider/example-model-b", reasoningEffort: "high" } as const;

type ModelOptionsView = {
  defaultProvider: string | null;
  defaultModel: string | null;
  providers: { provider: string; name: string; models: string[]; reasoningCapable: Record<string, boolean> }[];
  reasoningEfforts: string[];
};

export const modelSelectionScenario: Scenario = {
  name: "대화의 모델 선택",

  async run(context) {
    step("고를 수 있는 모델 목록은 기본값과 인증된 provider 만 주고, 다시 불러도 Hermes 를 한 번만 부른다");
    const callsBefore = context.hermes.modelOptionsCalls();
    const options = expectStatus(
      await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad }),
      200,
      "모델 목록",
    ).json<ModelOptionsView>();
    expect(
      options.defaultProvider === PROFILE_DEFAULT.provider && options.defaultModel === PROFILE_DEFAULT.model,
      `기본값이 profile 의 기본값이 아니다: ${JSON.stringify(options)}`,
    );
    expect(
      options.providers.length === 1 && options.providers[0]?.provider === PROFILE_DEFAULT.provider
        && options.providers[0]?.models.includes(PROFILE_DEFAULT.model) === true
        && options.providers[0]?.reasoningCapable["example-model-mini"] === false,
      `인증된 provider 하나만 와야 한다: ${JSON.stringify(options.providers)}`,
    );
    expect(
      options.reasoningEfforts.join(",") === "low,medium,high,xhigh,max",
      `effort 선택지가 다르다: ${JSON.stringify(options.reasoningEfforts)}`,
    );
    expectStatus(
      await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad }),
      200,
      "모델 목록 다시 조회",
    );
    const callsAfter = context.hermes.modelOptionsCalls();
    expect(
      callsAfter - callsBefore === 1,
      `모델 목록을 두 번 불렀는데 Hermes 는 ${callsAfter - callsBefore}번 불렸다`,
    );

    step("고르지 않은 대화는 provider 와 모델을 빼고 보낸다");
    const plain = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "기본값 검사", agentCode: "dad" },
      }),
      200,
      "기본값 대화",
    ).json<Turn>();
    const sentPlain = context.hermes.lastSubmittedRuntime();
    expect(
      sentPlain.provider === undefined && sentPlain.model === undefined
        && sentPlain.reasoningEffort === undefined,
      `기본값 대화의 요청에 모델이 실렸다: ${JSON.stringify(sentPlain)}`,
    );

    step("기본값 대화의 실행은 세션이 답한 profile 기본값을 적는다");
    const plainExecution = (await executionsOf(context)).find((execution) => execution.id === plain.executionId);
    expect(
      plainExecution?.provider === PROFILE_DEFAULT.provider && plainExecution?.model === PROFILE_DEFAULT.model,
      `세션의 기본값을 적지 않았다: ${JSON.stringify(plainExecution)}`,
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
    const probed = (await executionsOf(context)).find((execution) => execution.id === probe.executionId);
    expect(
      probed?.model === "example-provider/example-model-c" && probed?.provider === "nvidia",
      `실제로 돈 모델을 적지 않았다: ${JSON.stringify(probed)}`,
    );

    step("단계를 고르면 정의된 모델과 강도를 보내고 기본값 복귀는 요청 값을 비운다");
    const tierConversationId = await emptyConversation(context);
    expectStatus(await call(context, `/chat/conversations/${tierConversationId}/model-tier`, {
      method: "PUT",
      token: context.tokens.dad,
      body: { mode: "TIER", tier: "DEEP" },
    }), 200, "깊게 단계 선택");
    const tierTurn = expectStatus(await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { conversationId: tierConversationId, text: "단계 선택 검사" },
    }), 200, "단계 대화").json<Turn>();
    const tierRuntime = context.hermes.lastSubmittedRuntime();
    expect(tierRuntime.provider === PROFILE_DEFAULT.provider && tierRuntime.model === "gpt-6.1-sol"
      && tierRuntime.reasoningEffort === "high", `단계 정의가 실리지 않았다: ${JSON.stringify(tierRuntime)}`);
    const tierTree = expectStatus(await call(context, `/usage/executions/${tierTurn.executionId}/tree`, {
      token: context.tokens.dad,
    }), 200, "단계 실행 기록").json<{ root: { modelTier: string; model: string; reasoningEffortSource: string } }>();
    expect(tierTree.root.modelTier === "DEEP" && tierTree.root.model === "gpt-6.1-sol"
      && tierTree.root.reasoningEffortSource === "REQUESTED", "단계와 실제 요청 강도가 기록되지 않았다");
    expectStatus(await call(context, `/chat/conversations/${tierConversationId}/model-tier`, {
      method: "PUT",
      token: context.tokens.dad,
      body: { mode: "DEFAULT", tier: null },
    }), 200, "에이전트 기본값 복귀");
    expectStatus(await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { conversationId: tierConversationId, text: "단계 기본값 복귀 검사" },
    }), 200, "기본값 복귀 대화");
    const restored = context.hermes.lastSubmittedRuntime();
    expect(restored.provider === undefined && restored.model === undefined && restored.reasoningEffort === undefined,
      "에이전트 기본값 복귀 뒤에도 단계 값이 실렸다");

    step("대화에서 모델과 effort 를 고르면 그 값을 싣는다");
    const conversationId = await emptyConversation(context);
    await chooseModel(context, conversationId, CHOSEN);
    const chosen = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId, text: "고른 모델 검사" },
      }),
      200,
      "고른 모델 대화",
    ).json<Turn>();
    const sentChosen = context.hermes.lastSubmittedRuntime();
    expect(
      sentChosen.provider === CHOSEN.provider && sentChosen.model === CHOSEN.model
        && sentChosen.reasoningEffort === CHOSEN.reasoningEffort,
      `고른 provider, 모델, effort 가 실리지 않았다: ${JSON.stringify(sentChosen)}`,
    );
    const chosenExecution = (await executionsOf(context)).find((execution) => execution.id === chosen.executionId);
    expect(
      chosenExecution?.model === CHOSEN.model,
      `실행 목록의 모델이 고른 값이 아니다: ${JSON.stringify(chosenExecution)}`,
    );

    step("고른 모델의 provider 가 막히면 넘기지 않고 PROVIDER_BLOCKED 로 실패한다");
    context.hermes.blockProvider(CHOSEN.provider);
    try {
      const blocked = await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId, text: "막힘 검사" },
      });
      const body = blocked.json<{ code?: string }>();
      expect(
        blocked.status === 502 && body.code === "PROVIDER_BLOCKED",
        `막혔는데 PROVIDER_BLOCKED 로 실패하지 않았다: ${blocked.status} ${JSON.stringify(body)}`,
      );
      const sentBlocked = context.hermes.lastSubmittedRuntime();
      expect(
        sentBlocked.provider === CHOSEN.provider,
        `막힌 뒤 다른 provider 로 넘겼다: ${JSON.stringify(sentBlocked)}`,
      );
      const latest = (await executionsOf(context))[0];
      expect(
        latest?.status === "FAILED" && latest?.errorCode === "PROVIDER_BLOCKED"
          && latest?.provider === CHOSEN.provider,
        `막혀서 실패한 실행이 남지 않았다: ${JSON.stringify(latest)}`,
      );
    } finally {
      context.hermes.clearBlockedProviders();
    }
  },
};

async function emptyConversation(context: Parameters<Scenario["run"]>[0]): Promise<string> {
  return expectStatus(
    await call(context, "/chat/conversations", {
      method: "POST",
      token: context.tokens.dad,
      body: { agentCode: "dad" },
    }),
    200,
    "빈 대화 만들기",
  ).json<{ conversationId: string }>().conversationId;
}

async function chooseModel(
  context: Parameters<Scenario["run"]>[0],
  conversationId: string,
  choice: { provider: string; model: string; reasoningEffort: string },
): Promise<void> {
  expectStatus(
    await call(context, `/chat/conversations/${conversationId}/model`, {
      method: "PUT",
      token: context.tokens.dad,
      body: choice,
    }),
    200,
    "모델 고르기",
  );
}

async function executionsOf(context: Parameters<Scenario["run"]>[0]): Promise<ExecutionView[]> {
  return expectStatus(
    await call(context, "/usage/executions?limit=50", { token: context.tokens.dad }),
    200,
    "사용량 조회",
  ).json<ExecutionView[]>();
}
