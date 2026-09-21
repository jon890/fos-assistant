/** 화면에서 자기 에이전트의 성격을 읽고 쓰는 경로를 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { DAD_BINDING } from "./binding.ts";

type PersonaView = { body: string; bodyHash: string; editable: boolean; maxChars: number };

export const personaScenario: Scenario = {
  name: "에이전트 성격",

  async run(context) {
    step("아직 쓰지 않은 성격은 빈 본문으로 온다");
    const initial = expectStatus(
      await call(context, "/agents/dad/persona", { token: context.tokens.dad }),
      200,
      "성격 첫 조회",
    ).json<PersonaView>();
    expect(initial.body === "", "아직 쓰지 않은 성격이 비어 있지 않다");
    expect(initial.editable, "주인이 자기 에이전트의 성격을 고칠 수 없다고 나온다");

    step("성격을 쓰면 대역이 받은 본문이 그대로 남는다");
    const written = expectStatus(
      await call(context, "/agents/dad/persona", {
        method: "PUT",
        token: context.tokens.dad,
        body: { body: "차분하게 설명하는 성격이다", baseHash: initial.bodyHash },
      }),
      200,
      "성격 저장",
    ).json<PersonaView>();
    expect(
      context.hermes.soulOf(DAD_BINDING.profileName) === "차분하게 설명하는 성격이다",
      "대역이 받은 본문이 저장한 것과 다르다",
    );

    step("다시 읽으면 저장한 본문이 온다");
    const reread = expectStatus(
      await call(context, "/agents/dad/persona", { token: context.tokens.dad }),
      200,
      "성격 다시 조회",
    ).json<PersonaView>();
    expect(reread.body === "차분하게 설명하는 성격이다", "다시 읽은 본문이 저장한 것과 다르다");
    expect(reread.bodyHash === written.bodyHash, "다시 읽은 지문이 저장 응답의 지문과 다르다");

    step("낡은 baseHash 로 저장하면 거절되고 대역의 본문이 바뀌지 않는다");
    const stale = expectStatus(
      await call(context, "/agents/dad/persona", {
        method: "PUT",
        token: context.tokens.dad,
        body: { body: "낡은 지문으로 덮어쓰려는 성격이다", baseHash: initial.bodyHash },
      }),
      409,
      "낡은 지문으로 저장",
    );
    expect(
      stale.json<{ code: string }>().code === "PERSONA_STALE",
      `기대한 오류 코드가 아니다: ${stale.body}`,
    );
    expect(
      context.hermes.soulOf(DAD_BINDING.profileName) === "차분하게 설명하는 성격이다",
      "거절됐는데도 대역의 본문이 바뀌었다",
    );
  },
};
