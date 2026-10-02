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

/** 관리자가 에이전트 기본값으로 저장할 모델이다. 대역 catalog 에 있고 profile 기본값과 달라야 한다. */
const AGENT_DEFAULT = { provider: "openai-codex", model: "example-model-mini", reasoningEffort: "medium" } as const;

type ModelOptionsView = {
  defaultProvider: string | null;
  defaultModel: string | null;
  defaultReasoningEffort: string | null;
  defaultFromAgent: boolean;
  defaultAvailable: boolean;
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

    step("단계 정의는 DB 만 갖는다. 저장 전에는 세 단계가 비어 있고 관리자가 저장한 뒤 그 값을 쓴다");
    const emptyTiers = expectStatus(
      await call(context, "/chat/model-tiers?agentCode=dad", { token: context.tokens.dad }),
      200,
      "저장 전 단계 조회",
    ).json<{ tiers: { model: string | null }[] }>();
    expect(
      emptyTiers.tiers.length === 3 && emptyTiers.tiers.every((tier) => tier.model === null),
      `저장 전인데 단계에 모델이 있다: ${JSON.stringify(emptyTiers)}`,
    );
    expectStatus(await call(context, "/chat/model-tiers/group", {
      method: "PUT",
      token: context.tokens.dad,
      body: {
        tiers: [
          { tier: "FAST", provider: null, model: "example-fast", reasoningEffort: "low" },
          { tier: "BALANCED", provider: null, model: "example-balanced", reasoningEffort: "medium" },
          { tier: "DEEP", provider: null, model: "example-deep", reasoningEffort: "high" },
        ],
        defaultTier: null,
      },
    }), 204, "그룹 단계 저장");

    step("세 단계는 저장한 정의와 profile catalog로 해석하고 기본값 복귀는 요청 값을 비운다");
    const tierConversationId = await emptyConversation(context);
    const tierCases = [
      { tier: "FAST", model: "example-fast", effort: "low" },
      { tier: "BALANCED", model: "example-balanced", effort: "medium" },
      { tier: "DEEP", model: "example-deep", effort: "high" },
    ];
    for (const selected of tierCases) {
      expectStatus(await call(context, `/chat/conversations/${tierConversationId}/model-tier`, {
        method: "PUT",
        token: context.tokens.dad,
        body: { mode: "TIER", tier: selected.tier },
      }), 200, `${selected.tier} 단계 선택`);
      const tierTurn = expectStatus(await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: tierConversationId, text: "단계 선택 검사" },
      }), 200, "단계 대화").json<Turn>();

      const tierRuntime = context.hermes.lastSubmittedRuntime();
      expect(tierRuntime.provider === PROFILE_DEFAULT.provider && tierRuntime.model === selected.model
        && tierRuntime.reasoningEffort === selected.effort,
        `설정된 단계가 실리지 않았다: ${JSON.stringify(tierRuntime)}`);
      const tierTree = expectStatus(await call(context, `/usage/executions/${tierTurn.executionId}/tree`, {
        token: context.tokens.dad,
      }), 200, "단계 실행 기록").json<{ root: { modelTier: string; model: string; reasoningEffortSource: string } }>();
      expect(tierTree.root.modelTier === selected.tier && tierTree.root.model === selected.model
        && tierTree.root.reasoningEffortSource === "REQUESTED", "단계와 실제 요청 강도가 기록되지 않았다");
    }
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

    step("에이전트 기본 모델을 저장하면 고르지 않은 대화가 그 값을 명시해 보낸다");
    expectStatus(await call(context, "/admin/agents/dad/model-default", {
      method: "PUT",
      token: context.tokens.kid,
      body: AGENT_DEFAULT,
    }), 403, "관리자가 아닌 사용자의 기본 모델 저장");
    expectStatus(await call(context, "/admin/agents/dad/model-default", {
      method: "PUT",
      token: context.tokens.dad,
      body: { provider: PROFILE_DEFAULT.provider, model: "example-missing", reasoningEffort: null },
    }), 400, "목록에 없는 기본 모델 저장");
    expectStatus(await call(context, "/admin/agents/dad/model-default", {
      method: "PUT",
      token: context.tokens.dad,
      body: AGENT_DEFAULT,
    }), 200, "에이전트 기본 모델 저장");
    const withAgentDefault = expectStatus(
      await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad }),
      200,
      "기본 모델 저장 뒤 목록",
    ).json<ModelOptionsView>();
    expect(
      withAgentDefault.defaultModel === AGENT_DEFAULT.model && withAgentDefault.defaultFromAgent === true
        && withAgentDefault.defaultAvailable === true
        && withAgentDefault.defaultReasoningEffort === AGENT_DEFAULT.reasoningEffort,
      `목록의 기본 모델이 에이전트 기본값이 아니다: ${JSON.stringify(withAgentDefault)}`,
    );
    const defaultTurn = expectStatus(await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { conversationId: tierConversationId, text: "에이전트 기본 모델 검사" },
    }), 200, "에이전트 기본 모델 대화").json<Turn>();
    const sentDefault = context.hermes.lastSubmittedRuntime();
    expect(
      sentDefault.provider === AGENT_DEFAULT.provider && sentDefault.model === AGENT_DEFAULT.model
        && sentDefault.reasoningEffort === AGENT_DEFAULT.reasoningEffort,
      `에이전트 기본 모델이 실리지 않았다: ${JSON.stringify(sentDefault)}`,
    );
    const defaultTree = expectStatus(await call(context, `/usage/executions/${defaultTurn.executionId}/tree`, {
      token: context.tokens.dad,
    }), 200, "에이전트 기본 모델 실행 기록").json<{ root: { modelTier: string | null; reasoningEffortSource: string } }>();
    expect(
      defaultTree.root.modelTier === null && defaultTree.root.reasoningEffortSource === "AGENT_DEFAULT",
      `effort 출처가 에이전트 기본값이 아니다: ${JSON.stringify(defaultTree.root)}`,
    );

    step("숨긴 모델은 목록에서 빠지고, 고르거나 그 모델로 실행하면 바꾸지 않고 MODEL_HIDDEN 으로 거절한다");
    const hiddenEntries = [
      { provider: AGENT_DEFAULT.provider, model: AGENT_DEFAULT.model },
      { provider: CHOSEN.provider, model: null },
    ];
    expectStatus(await call(context, "/admin/model-hidden", {
      method: "PUT",
      token: context.tokens.kid,
      body: { entries: hiddenEntries },
    }), 403, "관리자가 아닌 사용자의 숨김 저장");
    expectStatus(await call(context, "/admin/model-hidden", {
      method: "PUT",
      token: context.tokens.dad,
      body: { entries: hiddenEntries },
    }), 204, "숨김 저장");
    try {
      const hiddenList = expectStatus(
        await call(context, "/admin/model-hidden", { token: context.tokens.dad }),
        200,
        "숨김 조회",
      ).json<{ entries: { provider: string; model: string | null }[] }>();
      expect(hiddenList.entries.length === 2, `숨김 목록이 저장한 것과 다르다: ${JSON.stringify(hiddenList)}`);
      const filtered = expectStatus(
        await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad }),
        200,
        "숨긴 뒤 목록",
      ).json<ModelOptionsView>();
      expect(
        filtered.providers[0]?.models.includes(AGENT_DEFAULT.model) === false
          && filtered.providers[0]?.models.includes(PROFILE_DEFAULT.model) === true
          && filtered.defaultAvailable === false,
        `숨긴 모델이 목록에 남았거나 기본 모델을 쓸 수 있다고 답했다: ${JSON.stringify(filtered)}`,
      );
      const settings = expectStatus(
        await call(context, "/admin/agents/dad/model-settings", { token: context.tokens.dad }),
        200,
        "관리자 모델 설정 조회",
      ).json<{ agentDefault: { model: string | null }; catalog: ModelOptionsView; hidden: { entries: unknown[] } }>();
      expect(
        settings.agentDefault.model === AGENT_DEFAULT.model
          && settings.catalog.providers[0]?.models.includes(AGENT_DEFAULT.model) === true
          && settings.catalog.defaultModel === PROFILE_DEFAULT.model && settings.hidden.entries.length === 2,
        `관리자 목록이 숨김을 적용했거나 값이 다르다: ${JSON.stringify(settings)}`,
      );
      const hiddenRun = await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: tierConversationId, text: "숨긴 기본 모델 검사" },
      });
      expect(
        hiddenRun.status === 409 && hiddenRun.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `숨긴 모델로 실행했는데 MODEL_HIDDEN 으로 거절하지 않았다: ${hiddenRun.status}`,
      );
      const hiddenExecution = (await executionsOf(context))[0];
      expect(
        hiddenExecution?.status === "FAILED" && hiddenExecution?.errorCode === "MODEL_HIDDEN",
        `거절한 실행이 남지 않았다: ${JSON.stringify(hiddenExecution)}`,
      );
      const hiddenChoice = await call(context, `/chat/conversations/${tierConversationId}/model`, {
        method: "PUT",
        token: context.tokens.dad,
        body: CHOSEN,
      });
      expect(
        hiddenChoice.status === 409 && hiddenChoice.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `숨긴 provider 의 모델을 고를 수 있었다: ${hiddenChoice.status}`,
      );
    } finally {
      expectStatus(await call(context, "/admin/model-hidden", {
        method: "PUT",
        token: context.tokens.dad,
        body: { entries: [] },
      }), 204, "숨김 비우기");
      expectStatus(await call(context, "/admin/agents/dad/model-default", {
        method: "PUT",
        token: context.tokens.dad,
        body: { provider: null, model: null, reasoningEffort: null },
      }), 200, "에이전트 기본 모델 비우기");
    }
    const restoredOptions = expectStatus(
      await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad }),
      200,
      "기본 모델을 비운 뒤 목록",
    ).json<ModelOptionsView>();
    expect(
      restoredOptions.defaultModel === PROFILE_DEFAULT.model && restoredOptions.defaultFromAgent === false,
      `기본 모델을 비웠는데 profile 의 값으로 돌아가지 않았다: ${JSON.stringify(restoredOptions)}`,
    );

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
