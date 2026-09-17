/** 구독형 바인딩의 실행도 API 가격으로 환산해 기록하고 합계를 내는지 본다. */
import { call, expect, expectStatus, step, type Response, type Scenario } from "../harness.ts";
import { DAD_BINDING } from "./binding.ts";
import { CHAT_TURNS } from "./chat.ts";
import { MEMORY_CONTEXT_TURNS } from "./memory.ts";

/**
 * 실행 한 번의 환산 금액이다.
 *
 * <p>fake Hermes 는 실제 Hermes 처럼 model 자리에 profile 이름을 넣고 provider 를 주지 않는다.
 * 그래서 기록에 남는 것은 바인딩의 `openai-codex` 와 `gpt-5.5` 다.
 *
 * <p>입력 120 중 80 이 캐시이고 출력이 40 이다. 표본 카탈로그의 `gpt-5.5` 단가로 환산하면
 * `40 × 5 + 80 × 0.5 + 40 × 30` 이 되어 1440 마이크로 달러다.
 */
const MICROS_PER_RUN = 1440;
const HELD_RUN_TIMEOUT_MS = 5_000;
const COMPLETED_TURNS = CHAT_TURNS + MEMORY_CONTEXT_TURNS;

type ExecutionView = {
  id: number;
  agentCode: string;
  agentName: string;
  provider: string | null;
  model: string | null;
  costMode: string;
  status: string;
  contextChars: number;
  estimatedCostMicros: number | null;
  costCurrency: string | null;
  pricingVersion: string | null;
};

type MonthlyCostView = {
  month: string;
  currency: string;
  estimatedCostMicros: number;
  pricedExecutions: number;
  unpricedExecutions: number;
};

export const usageCostScenario: Scenario = {
  name: "사용량과 환산 비용",

  async run(context) {
    step("실행마다 바인딩의 provider 와 모델이 남는다");
    const executions = expectStatus(
      await call(context, "/usage/executions?limit=10", { token: context.tokens.dad }),
      200,
      "사용량 조회",
    ).json<ExecutionView[]>();

    expect(
      executions.length === COMPLETED_TURNS,
      `실행 ${COMPLETED_TURNS}건을 기대했는데 ${executions.length}건이다`,
    );
    for (const execution of executions) {
      expect(
        execution.agentCode === "dad" && execution.agentName === "Dad",
        `사용량에 에이전트가 담기지 않았다: ${execution.agentCode} / ${execution.agentName}`,
      );
      expect(
        execution.provider === DAD_BINDING.provider && execution.model === DAD_BINDING.model,
        `바인딩의 provider 와 모델이 기록되지 않았다: ${execution.provider} / ${execution.model}`,
      );
      expect(typeof execution.contextChars === "number", "사용량 응답에 contextChars 가 없다");
    }

    step("구독형 바인딩의 실행도 API 가격으로 환산해 적는다");
    for (const execution of executions) {
      expect(execution.costMode === "SUBSCRIPTION", `구독형 바인딩이 아니다: ${execution.costMode}`);
      expect(
        execution.estimatedCostMicros === MICROS_PER_RUN,
        `환산 금액이 ${MICROS_PER_RUN} 이 아니다: ${execution.estimatedCostMicros}`,
      );
      expect(execution.costCurrency === "USD", `통화가 USD 가 아니다: ${execution.costCurrency}`);
      expect(
        execution.pricingVersion?.startsWith("models.dev@") === true,
        `가격표를 적지 않았다: ${execution.pricingVersion}`,
      );
    }

    step("이번 달 합계는 저장된 금액을 더한 값이다");
    const monthly = expectStatus(
      await call(context, "/usage/monthly-cost", { token: context.tokens.dad }),
      200,
      "합계 조회",
    ).json<MonthlyCostView>();

    const expected = MICROS_PER_RUN * COMPLETED_TURNS;
    expect(
      monthly.estimatedCostMicros === expected && monthly.pricedExecutions === COMPLETED_TURNS,
      `합계가 실행 ${COMPLETED_TURNS}건의 ${expected} 이 아니다: ${JSON.stringify(monthly)}`,
    );
    expect(
      monthly.unpricedExecutions === 0,
      `가격을 찾지 못한 실행이 있다: ${JSON.stringify(monthly)}`,
    );
    expect(monthly.currency === "USD", `합계의 통화가 USD 가 아니다: ${monthly.currency}`);

    step("돌고 있는 실행은 목록에 보이지만 가격을 찾지 못한 실행으로 세지 않는다");
    const pendingController = new AbortController();
    let pendingTurn: Promise<Response> | undefined;
    try {
      context.hermes.holdNextRun();
      pendingTurn = call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "실행 중 상태 검사", agentCode: "dad" },
        signal: pendingController.signal,
      });
      await waitForHeldRun(context);

      const running = expectStatus(
        await call(context, "/usage/executions?limit=10", { token: context.tokens.dad }),
        200,
        "실행 중 사용량 조회",
      ).json<ExecutionView[]>();
      expect(running.some((execution) => execution.status === "RUNNING"), "RUNNING 실행이 목록에 없다");

      const whileRunning = expectStatus(
        await call(context, "/usage/monthly-cost", { token: context.tokens.dad }),
        200,
        "실행 중 합계 조회",
      ).json<MonthlyCostView>();
      expect(
        whileRunning.unpricedExecutions === 0,
        `RUNNING 실행이 가격 미확인으로 세어졌다: ${JSON.stringify(whileRunning)}`,
      );
    } finally {
      context.hermes.releaseHeldRun();
      if (pendingTurn !== undefined) {
        const response = await waitForPendingTurn(pendingTurn, pendingController);
        expectStatus(response, 200, "유지했던 대화");
      }
    }
  },
};

async function waitForPendingTurn(
  pendingTurn: Promise<Response>,
  controller: AbortController,
): Promise<Response> {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      pendingTurn,
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => {
          controller.abort();
          reject(new Error(`보류 실행 요청이 ${HELD_RUN_TIMEOUT_MS}ms 안에 끝나지 않았다`));
        }, HELD_RUN_TIMEOUT_MS);
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}

async function waitForHeldRun(context: Parameters<Scenario["run"]>[0]): Promise<void> {
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([
      context.hermes.waitForHeldRun(),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(
          () => reject(new Error(`Hermes 가 ${HELD_RUN_TIMEOUT_MS}ms 안에 보류 실행을 받지 못했다`)),
          HELD_RUN_TIMEOUT_MS,
        );
      }),
    ]);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
  }
}
