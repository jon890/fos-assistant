/** 새 대화 화면의 추천 질문을 모델이 만들어 돌려주는지 본다. 사람이 적던 경로와 목록 칸은 없다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { FAKE_STARTER_PROMPTS } from "../fake-hermes.ts";

type StartersView = { prompts: string[]; status: "READY" | "GENERATING" | "NONE" };

/**
 * 이 시나리오가 `dad` 에게 남기는 뿌리 실행 수다. 추천을 만드는 실행도 사용량에 남는다(ADR-036).
 *
 * <p>대화를 마칠 때 다시 만드는 것은 추천이 하루보다 오래됐을 때뿐이라 뒤 시나리오에서는 더 늘지 않는다.
 */
export const STARTER_RUNS = 1;

const POLL_INTERVAL_MS = 1_000;
const READY_TIMEOUT_MS = 5_000;

function same(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export const startersScenario: Scenario = {
  name: "추천 질문",

  async run(context) {
    step("처음 읽으면 만드는 중이라고 답한다");
    const first = expectStatus(
      await call(context, "/agents/dad/starters", { token: context.tokens.dad }),
      200,
      "추천 질문 첫 조회",
    ).json<StartersView>();
    expect(first.status === "GENERATING", `처음 읽은 상태가 GENERATING 이 아니다: ${JSON.stringify(first)}`);
    expect(first.prompts.length === 0, `만드는 중인데 추천이 왔다: ${JSON.stringify(first.prompts)}`);

    step("다시 읽으면 모델이 만든 넷이 온다");
    const deadline = Date.now() + READY_TIMEOUT_MS;
    let latest = first;
    while (latest.status !== "READY" && Date.now() < deadline) {
      await new Promise((done) => setTimeout(done, POLL_INTERVAL_MS));
      latest = expectStatus(
        await call(context, "/agents/dad/starters", { token: context.tokens.dad }),
        200,
        "추천 질문 다시 조회",
      ).json<StartersView>();
    }
    expect(latest.status === "READY", `${READY_TIMEOUT_MS}ms 안에 READY 가 되지 않았다: ${JSON.stringify(latest)}`);
    expect(
      same(latest.prompts, FAKE_STARTER_PROMPTS),
      `추천 질문이 모델의 답과 다르다: ${JSON.stringify(latest.prompts)}`,
    );

    step("추천을 쓰는 경로는 없다");
    const written = await call(context, "/agents/dad/starters", {
      method: "PUT",
      token: context.tokens.dad,
      body: { tagline: "소개", starterPrompts: ["적은 질문"] },
    });
    expect(
      written.status === 405 || written.status === 404,
      `추천을 쓰는 PUT 이 거절되지 않았다: ${written.status} ${written.body}`,
    );

    step("에이전트 목록에 소개와 추천 칸이 없다");
    const agents = expectStatus(
      await call(context, "/agents", { token: context.tokens.dad }),
      200,
      "에이전트 목록",
    ).json<Record<string, unknown>[]>();
    const dad = agents.find((agent) => agent.code === "dad");
    expect(dad !== undefined, "에이전트 목록에 dad 가 없다");
    expect(!("tagline" in dad!), `목록에 tagline 이 남아 있다: ${JSON.stringify(dad)}`);
    expect(!("starterPrompts" in dad!), `목록에 starterPrompts 가 남아 있다: ${JSON.stringify(dad)}`);

    step("볼 수 없는 사용자에게는 없는 에이전트와 같은 응답을 준다");
    const hidden = expectStatus(
      await call(context, "/agents/dad/starters", { token: context.tokens.kid }),
      404,
      "kid 가 dad 의 추천 질문 조회",
    );
    expect(
      hidden.json<{ code: string }>().code === "AGENT_NOT_FOUND",
      `기대한 오류 코드가 아니다: ${hidden.body}`,
    );
  },
};
