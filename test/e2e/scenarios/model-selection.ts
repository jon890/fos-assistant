/**
 * 실행이 대화가 고른 칸의 모델과 effort 로 Hermes 를 부르고, 실제로 돈 모델을 적고, 막혀도 넘기지 않는 것을 본다.
 *
 * <p>사용자는 슬라이더 칸만 고른다(ADR-20261101 / model-setting). 대화에 provider 와 모델을 직접 저장하던 {@code CUSTOM}
 * 은 없다. 칸을 고르면 그 사용자의 기본 칸도 바뀌므로, 기본 칸이 없어야 하는 검사를 칸을 고르기 전에 둔다.
 *
 * <p>이 시나리오는 사용량 합계를 검사하는 시나리오보다 뒤에 돈다. 여기서 실행을 더 만들기 때문이다.
 */
import { call, expect, expectStatus, fail, step, type Scenario } from "../harness.ts";

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

/** provider 전체를 숨기는 검사에 쓰는 provider 다. 세션 조회가 답하는 다른 provider 다. */
const OTHER_PROVIDER = "nvidia";

/** 관리자가 숨길 모델이다. 대역 catalog 에 있고 HIGH 칸이 가리키는 모델이다. */
const HIDDEN = { provider: "openai-codex", model: "example-deep" } as const;

/** 관리자가 칸에 고를 수 있는 그룹 모델 목록이다. 숨긴 모델은 빠진 채 온다. */
type GroupCatalogView = {
  catalog: {
    providers: {
      provider: string;
      name: string;
      models: string[];
      reasoning: Record<string, { support: string; disable: string }>;
      reasoningCapable: Record<string, boolean>;
    }[];
    reasoningEfforts: string[];
  } | null;
};

/** 이 시나리오가 저장하는 칸 정의다. 모델 이름은 대역 catalog 에 있는 것이다. */
const GROUP_TIERS = [
  { tier: "INSTANT", provider: "openai-codex", model: "example-fast", reasoningEffort: "low" },
  { tier: "MEDIUM", provider: "openai-codex", model: "example-balanced", reasoningEffort: "medium" },
  { tier: "HIGH", provider: "openai-codex", model: "example-deep", reasoningEffort: "high" },
  { tier: "EXTRA_HIGH", provider: null, model: null, reasoningEffort: null },
  { tier: "PRO", provider: null, model: null, reasoningEffort: null },
];

export const modelSelectionScenario: Scenario = {
  name: "대화의 모델 선택",

  async run(context) {
    step("그룹 모델 목록은 인증된 provider 만 주고, 다시 불러도 Hermes 를 더 부르지 않는다");
    const options = await groupCatalog(context, "그룹 모델 목록");
    const callsBefore = context.hermes.modelOptionsCalls();
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
      await call(context, "/admin/model-catalog", { token: context.tokens.kid }),
      403,
      "관리자가 아닌 사용자의 그룹 모델 목록 조회",
    );

    step("reasoning 표는 Hermes 가 밝힌 값만 SUPPORTED 나 UNSUPPORTED 로 주고 밝히지 않은 칸은 UNKNOWN 이다");
    const expectedReasoning: Record<string, { support: string; disable: string }> = {
      [PROFILE_DEFAULT.model]: { support: "SUPPORTED", disable: "SUPPORTED" },
      "example-model-mini": { support: "UNSUPPORTED", disable: "UNKNOWN" },
      "example-fast": { support: "SUPPORTED", disable: "UNKNOWN" },
      "example-balanced": { support: "UNKNOWN", disable: "UNKNOWN" },
      "example-deep": { support: "SUPPORTED", disable: "UNSUPPORTED" },
    };
    const reasoning = options.providers[0]?.reasoning ?? {};
    for (const [model, expected] of Object.entries(expectedReasoning)) {
      expect(
        reasoning[model]?.support === expected.support && reasoning[model]?.disable === expected.disable,
        `${model} 의 reasoning 이 ${JSON.stringify(expected)} 가 아니다: ${JSON.stringify(reasoning[model])}`,
      );
    }

    await groupCatalog(context, "그룹 모델 목록 다시 조회");
    const callsAfter = context.hermes.modelOptionsCalls();
    expect(
      callsAfter === callsBefore,
      `그룹 모델 목록을 다시 불렀는데 Hermes 가 ${callsAfter - callsBefore}번 더 불렸다`,
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

    step("칸 정의는 DB 만 갖는다. 저장 전에는 다섯 칸이 비어 고를 칸이 없고 관리자가 저장한 뒤 그 값을 쓴다");
    const emptyTiers = expectStatus(
      await call(context, "/chat/model-tiers?agentCode=dad", { token: context.tokens.dad }),
      200,
      "저장 전 단계 조회",
    ).json<{ tiers: { model: string | null }[] }>();
    expect(
      emptyTiers.tiers.length === 0,
      `저장 전인데 고를 칸이 있다: ${JSON.stringify(emptyTiers)}`,
    );
    expectStatus(await call(context, "/chat/model-tiers/group", {
      method: "PUT",
      token: context.tokens.dad,
      body: { tiers: GROUP_TIERS, defaultTier: null },
    }), 204, "그룹 단계 저장");

    step("사용자와 그룹 기본 칸이 없으면 모델을 싣지 않고 그룹 기본 칸을 정하면 선택 없는 대화가 그 칸으로 돈다");
    const inheritedConversationId = await emptyConversation(context);
    expectStatus(await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { conversationId: inheritedConversationId, text: "기본 칸이 없는 대화 검사" },
    }), 200, "기본 칸이 없는 대화");
    const noDefault = context.hermes.lastSubmittedRuntime();
    expect(noDefault.provider === undefined && noDefault.model === undefined && noDefault.reasoningEffort === undefined,
      `기본 칸이 없는데 모델이 실렸다: ${JSON.stringify(noDefault)}`);
    expectStatus(await call(context, "/chat/model-tiers/group", {
      method: "PUT",
      token: context.tokens.dad,
      body: { tiers: GROUP_TIERS, defaultTier: "MEDIUM" },
    }), 204, "그룹 기본 칸 저장");
    try {
      const inheritedTurn = expectStatus(await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: inheritedConversationId, text: "그룹 기본 칸 대화 검사" },
      }), 200, "그룹 기본 칸 대화").json<Turn>();
      const groupDefault = context.hermes.lastSubmittedRuntime();
      expect(groupDefault.provider === PROFILE_DEFAULT.provider && groupDefault.model === "example-balanced"
        && groupDefault.reasoningEffort === "medium",
        `그룹 기본 칸의 값이 실리지 않았다: ${JSON.stringify(groupDefault)}`);
      const inheritedTree = expectStatus(await call(context, `/usage/executions/${inheritedTurn.executionId}/tree`, {
        token: context.tokens.dad,
      }), 200, "그룹 기본 칸 실행 기록").json<{ root: { modelTier: string | null; reasoningEffortSource: string } }>();
      expect(inheritedTree.root.modelTier === "MEDIUM" && inheritedTree.root.reasoningEffortSource === "REQUESTED",
        `칸과 effort 출처가 다르다: ${JSON.stringify(inheritedTree.root)}`);
    } finally {
      expectStatus(await call(context, "/chat/model-tiers/group", {
        method: "PUT",
        token: context.tokens.dad,
        body: { tiers: GROUP_TIERS, defaultTier: null },
      }), 204, "그룹 기본 칸 비우기");
    }

    step("profile 의 기본 모델을 숨기면 모델을 싣지 않는 실행도 제출하지 않고 MODEL_HIDDEN 으로 거절한다");
    expectStatus(await call(context, "/admin/model-hidden", {
      method: "PUT",
      token: context.tokens.dad,
      body: { entries: [{ provider: PROFILE_DEFAULT.provider, model: PROFILE_DEFAULT.model }] },
    }), 204, "profile 기본 모델 숨김 저장");
    try {
      const submitsBefore = context.hermes.submitCount();
      // 사용자와 그룹 기본 칸이 모두 없는 새 대화는 모델을 싣지 않고 profile 의 기본 모델로 돈다.
      const hiddenDefaultRun = await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: await emptyConversation(context), text: "숨긴 profile 기본 모델 검사" },
      });
      expect(
        hiddenDefaultRun.status === 409 && hiddenDefaultRun.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `profile 기본 모델을 숨겼는데 MODEL_HIDDEN 으로 거절하지 않았다: ${hiddenDefaultRun.status}`,
      );
      const hiddenDefaultExecution = (await executionsOf(context))[0];
      expect(
        hiddenDefaultExecution?.status === "FAILED" && hiddenDefaultExecution?.errorCode === "MODEL_HIDDEN",
        `거절한 기본값 실행이 남지 않았다: ${JSON.stringify(hiddenDefaultExecution)}`,
      );
      expect(
        context.hermes.submitCount() === submitsBefore,
        `거절한 실행을 Hermes 에 제출했다: ${context.hermes.submitCount() - submitsBefore}번`,
      );
    } finally {
      expectStatus(await call(context, "/admin/model-hidden", {
        method: "PUT",
        token: context.tokens.dad,
        body: { entries: [] },
      }), 204, "profile 기본 모델 숨김 비우기");
    }

    step("세 칸은 저장한 정의와 profile catalog로 해석하고 DEFAULT 와 CUSTOM 선택은 거절한다");
    const tierConversationId = await emptyConversation(context);
    const tierCases = [
      { tier: "INSTANT", model: "example-fast", effort: "low" },
      { tier: "MEDIUM", model: "example-balanced", effort: "medium" },
      { tier: "HIGH", model: "example-deep", effort: "high" },
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
    }), 400, "DEFAULT 선택 저장");
    const custom = await call(context, `/chat/conversations/${tierConversationId}/model-tier`, {
      method: "PUT",
      token: context.tokens.dad,
      body: { mode: "CUSTOM", tier: null },
    });
    expect(
      custom.status === 400 && custom.json<{ code?: string }>().code === "VALIDATION_FAILED",
      `CUSTOM 선택을 VALIDATION_FAILED 로 거절하지 않았다: ${custom.status} ${custom.body}`,
    );

    step("provider 와 모델을 대화에 직접 저장하던 경로는 없다");
    const removedChoice = await call(context, `/chat/conversations/${tierConversationId}/model`, {
      method: "PUT",
      token: context.tokens.dad,
      body: { provider: PROFILE_DEFAULT.provider, model: PROFILE_DEFAULT.model, reasoningEffort: "high" },
    });
    expect(
      removedChoice.status >= 400 && removedChoice.status < 500,
      `지운 모델 직접 저장 경로가 답했다: ${removedChoice.status}`,
    );
    const removedOptions = await call(context, "/chat/model-options?agentCode=dad", { token: context.tokens.dad });
    expect(
      removedOptions.status >= 400 && removedOptions.status < 500,
      `지운 모델 목록 경로가 답했다: ${removedOptions.status}`,
    );

    step("칸을 고르면 사용자 기본 칸도 그 칸이 되어 선택 없는 새 대화가 그 칸으로 돈다");
    const tiersAfterChoice = expectStatus(
      await call(context, "/chat/model-tiers?agentCode=dad", { token: context.tokens.dad }),
      200,
      "칸을 고른 뒤 단계 조회",
    ).json<{ userDefaultTier: string | null }>();
    expect(
      tiersAfterChoice.userDefaultTier === "HIGH",
      `마지막에 고른 칸이 사용자 기본 칸이 되지 않았다: ${JSON.stringify(tiersAfterChoice)}`,
    );
    expectStatus(await call(context, "/chat/messages", {
      method: "POST",
      token: context.tokens.dad,
      body: { conversationId: await emptyConversation(context), text: "사용자 기본 칸 대화 검사" },
    }), 200, "사용자 기본 칸 대화");
    const userDefault = context.hermes.lastSubmittedRuntime();
    expect(userDefault.provider === PROFILE_DEFAULT.provider && userDefault.model === "example-deep"
      && userDefault.reasoningEffort === "high",
      `사용자 기본 칸의 값이 실리지 않았다: ${JSON.stringify(userDefault)}`);

    step("숨긴 모델은 목록에서 빠지고, 고르거나 그 모델로 실행하면 바꾸지 않고 MODEL_HIDDEN 으로 거절한다");
    const hiddenEntries = [
      { provider: HIDDEN.provider, model: HIDDEN.model },
      { provider: OTHER_PROVIDER, model: null },
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
      const filtered = await groupCatalog(context, "숨긴 뒤 그룹 모델 목록");
      expect(
        filtered.providers[0]?.models.includes(HIDDEN.model) === false
          && filtered.providers[0]?.models.includes(PROFILE_DEFAULT.model) === true
          && filtered.providers.every((provider) => provider.provider !== OTHER_PROVIDER),
        `숨긴 모델이 그룹 모델 목록에 남았거나 숨기지 않은 기본 모델이 빠졌다: ${JSON.stringify(filtered)}`,
      );
      const candidates = expectStatus(
        await call(context, "/admin/model-hidden/candidates", { token: context.tokens.dad }),
        200,
        "숨길 모델의 후보 조회",
      ).json<{ catalog: { providers: { models: string[] }[] } | null }>();
      expect(
        candidates.catalog?.providers[0]?.models.includes(HIDDEN.model) === true,
        `숨길 모델의 후보가 숨긴 모델을 뺐다: ${JSON.stringify(candidates)}`,
      );
      expectStatus(
        await call(context, "/admin/model-hidden/candidates", { token: context.tokens.kid }),
        403,
        "관리자가 아닌 사용자의 후보 조회",
      );
      const hiddenRun = await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: tierConversationId, text: "숨긴 칸 모델 검사" },
      });
      expect(
        hiddenRun.status === 409 && hiddenRun.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `숨긴 칸 모델로 실행했는데 MODEL_HIDDEN 으로 거절하지 않았다: ${hiddenRun.status}`,
      );
      const hiddenExecution = (await executionsOf(context))[0];
      expect(
        hiddenExecution?.status === "FAILED" && hiddenExecution?.errorCode === "MODEL_HIDDEN",
        `거절한 실행이 남지 않았다: ${JSON.stringify(hiddenExecution)}`,
      );
      const hiddenChoice = await call(context, `/chat/conversations/${tierConversationId}/model-tier`, {
        method: "PUT",
        token: context.tokens.dad,
        body: { mode: "TIER", tier: "HIGH" },
      });
      expect(
        hiddenChoice.status === 409 && hiddenChoice.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `숨긴 모델을 가리키는 칸을 고를 수 있었다: ${hiddenChoice.status}`,
      );
      const hiddenTierSave = await call(context, "/chat/model-tiers/group", {
        method: "PUT",
        token: context.tokens.dad,
        body: {
          tiers: GROUP_TIERS.map((tier) =>
            tier.tier === "PRO" ? { ...tier, ...HIDDEN, reasoningEffort: "high" } : tier),
          defaultTier: null,
        },
      });
      expect(
        hiddenTierSave.status === 409 && hiddenTierSave.json<{ code?: string }>().code === "MODEL_HIDDEN",
        `숨긴 모델로 칸을 바꿀 수 있었다: ${hiddenTierSave.status}`,
      );
    } finally {
      expectStatus(await call(context, "/admin/model-hidden", {
        method: "PUT",
        token: context.tokens.dad,
        body: { entries: [] },
      }), 204, "숨김 비우기");
    }
    const restoredOptions = await groupCatalog(context, "숨김을 비운 뒤 그룹 모델 목록");
    expect(
      restoredOptions.providers[0]?.models.includes(HIDDEN.model) === true,
      `숨김을 비웠는데 그룹 모델 목록이 돌아오지 않았다: ${JSON.stringify(restoredOptions)}`,
    );

    step("고른 칸의 provider 가 막히면 넘기지 않고 PROVIDER_BLOCKED 로 실패한다");
    // 숨김을 풀었으므로 HIGH 칸으로 다시 돈다.
    context.hermes.blockProvider(PROFILE_DEFAULT.provider);
    try {
      const blocked = await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { conversationId: tierConversationId, text: "막힘 검사" },
      });
      const body = blocked.json<{ code?: string }>();
      expect(
        blocked.status === 502 && body.code === "PROVIDER_BLOCKED",
        `막혔는데 PROVIDER_BLOCKED 로 실패하지 않았다: ${blocked.status} ${JSON.stringify(body)}`,
      );
      const sentBlocked = context.hermes.lastSubmittedRuntime();
      expect(
        sentBlocked.provider === PROFILE_DEFAULT.provider && sentBlocked.model === "example-deep",
        `막힌 뒤 다른 provider 나 모델로 넘겼다: ${JSON.stringify(sentBlocked)}`,
      );
      const latest = (await executionsOf(context))[0];
      expect(
        latest?.status === "FAILED" && latest?.errorCode === "PROVIDER_BLOCKED"
          && latest?.provider === PROFILE_DEFAULT.provider,
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

async function groupCatalog(
  context: Parameters<Scenario["run"]>[0],
  what: string,
): Promise<NonNullable<GroupCatalogView["catalog"]>> {
  const { catalog } = expectStatus(
    await call(context, "/admin/model-catalog", { token: context.tokens.dad }),
    200,
    what,
  ).json<GroupCatalogView>();
  if (catalog === null) fail(`${what}: Hermes 의 목록을 읽지 못했다`);
  return catalog;
}

async function executionsOf(context: Parameters<Scenario["run"]>[0]): Promise<ExecutionView[]> {
  return expectStatus(
    await call(context, "/usage/executions?limit=50", { token: context.tokens.dad }),
    200,
    "사용량 조회",
  ).json<ExecutionView[]>();
}
