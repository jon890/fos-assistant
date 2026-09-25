/** 에이전트의 한 줄 소개와 추천 질문을 쓰고, 새 대화 화면이 쓰는 목록에 실려 오는지 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type StartersView = {
  tagline: string | null;
  starterPrompts: string[];
  editable: boolean;
  maxPrompts: number;
};
type AgentView = { code: string; tagline: string | null; starterPrompts: string[] };

const TAGLINE = "집안일과 일정을 돕는다";
const PROMPTS = ["오늘 할 일을 정리해 줘", "이번 주 장보기 목록을 만들어 줘"];

function same(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

export const startersScenario: Scenario = {
  name: "에이전트 소개와 추천 질문",

  async run(context) {
    step("주인이 소개와 추천 질문을 쓰면 다듬은 값이 돌아온다");
    const written = expectStatus(
      await call(context, "/agents/dad/starters", {
        method: "PUT",
        token: context.tokens.dad,
        body: { tagline: `  ${TAGLINE}  `, starterPrompts: [PROMPTS[0], " ", PROMPTS[1]] },
      }),
      200,
      "소개와 추천 질문 저장",
    ).json<StartersView>();
    expect(written.tagline === TAGLINE, `저장 응답의 소개가 다르다: ${written.tagline}`);
    expect(
      same(written.starterPrompts, PROMPTS),
      `저장 응답의 추천 질문이 다르다: ${JSON.stringify(written.starterPrompts)}`,
    );
    expect(written.editable, "주인이 자기 에이전트의 추천 질문을 고칠 수 없다고 나온다");

    step("다시 읽으면 같은 값이 온다");
    const reread = expectStatus(
      await call(context, "/agents/dad/starters", { token: context.tokens.dad }),
      200,
      "소개와 추천 질문 다시 조회",
    ).json<StartersView>();
    expect(reread.tagline === TAGLINE, `다시 읽은 소개가 다르다: ${reread.tagline}`);
    expect(
      same(reread.starterPrompts, PROMPTS),
      `다시 읽은 추천 질문이 다르다: ${JSON.stringify(reread.starterPrompts)}`,
    );
    expect(reread.maxPrompts === 4, `추천 질문 최대 수가 4 가 아니다: ${reread.maxPrompts}`);

    step("에이전트 목록의 dad 줄에 소개와 추천 질문이 실려 온다");
    const agents = expectStatus(
      await call(context, "/agents", { token: context.tokens.dad }),
      200,
      "에이전트 목록",
    ).json<AgentView[]>();
    const dad = agents.find((agent) => agent.code === "dad");
    expect(dad !== undefined, "에이전트 목록에 dad 가 없다");
    expect(dad?.tagline === TAGLINE, `목록의 소개가 다르다: ${dad?.tagline}`);
    expect(
      same(dad?.starterPrompts ?? [], PROMPTS),
      `목록의 추천 질문이 다르다: ${JSON.stringify(dad?.starterPrompts)}`,
    );

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
