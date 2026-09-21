/**
 * 관리자가 사람을 더하고, 그 사람이 처음 들어와 에이전트를 받는 데까지 본다.
 *
 * <p>웹 계층은 여기서 띄우지 않으므로 「로그인한다」 대신 그 사람의 메일 주소로 만든 토큰으로 부른다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

/** 이 시나리오가 더하는 사람이다. 러너가 같은 주소로 토큰을 만든다. */
export const NEW_PERSON = {
  email: "aunt@example.com",
  displayName: "이모",
  hermesProfile: "aunt",
} as const;

type PersonView = {
  id: number;
  email: string;
  hermesProfile: string;
  enabled: boolean;
  joined: boolean;
};

type AgentView = { code: string; model: string; visibility: string };

export const peopleScenario: Scenario = {
  name: "사람을 더하고 첫 로그인에 에이전트가 생긴다",

  async run(context) {
    step("관리자가 아닌 사람은 더하지 못한다");
    expectStatus(
      await call(context, "/admin/people", {
        method: "POST",
        token: context.tokens.kid,
        body: NEW_PERSON,
      }),
      403,
      "member 의 사람 더하기",
    );

    step("관리자가 더하면 아직 들어온 적 없는 사람으로 목록에 들어간다");
    const added = expectStatus(
      await call(context, "/admin/people", {
        method: "POST",
        token: context.tokens.dad,
        body: NEW_PERSON,
      }),
      200,
      "사람 더하기",
    ).json<PersonView>();
    expect(added.email === NEW_PERSON.email, `더한 사람의 주소가 다르다: ${added.email}`);
    expect(added.enabled, "더한 사람이 꺼진 상태로 들어갔다");
    expect(!added.joined, "아직 들어온 적 없는 사람이 들어온 것으로 들어갔다");

    step("그 사람의 Hermes profile 과 key 가 만들어졌다");
    expect(
      context.hermes.profiles().includes(NEW_PERSON.hermesProfile),
      `Hermes 에 profile 이 만들어지지 않았다: ${context.hermes.profiles().join(", ")}`,
    );

    step("같은 이메일을 다시 더하면 거절한다");
    const sameEmail = expectStatus(
      await call(context, "/admin/people", {
        method: "POST",
        token: context.tokens.dad,
        body: { ...NEW_PERSON, hermesProfile: "another" },
      }),
      409,
      "같은 이메일",
    );
    expect(
      sameEmail.json<{ code: string }>().code === "PERSON_EMAIL_TAKEN",
      `겹친 이메일의 오류 코드가 다르다: ${sameEmail.body}`,
    );

    step("같은 profile 이름을 다시 쓰면 거절한다");
    const sameProfile = expectStatus(
      await call(context, "/admin/people", {
        method: "POST",
        token: context.tokens.dad,
        body: { ...NEW_PERSON, email: "uncle@example.com" },
      }),
      409,
      "같은 profile 이름",
    );
    expect(
      sameProfile.json<{ code: string }>().code === "PERSON_PROFILE_TAKEN",
      `겹친 profile 이름의 오류 코드가 다르다: ${sameProfile.body}`,
    );

    step("허용 목록에 더해진 주소는 로그인 판정을 통과한다");
    const allowed = expectStatus(
      await call(context, "/signin/allowed", {
        method: "POST",
        token: context.tokens.signin,
        body: { email: NEW_PERSON.email },
      }),
      200,
      "더해진 주소의 로그인 판정",
    ).json<{ allowed: boolean; hermesProfile: string }>();
    expect(allowed.allowed, "더해진 주소가 로그인 판정을 통과하지 못했다");
    expect(
      allowed.hermesProfile === NEW_PERSON.hermesProfile,
      `로그인 판정이 알려준 profile 이 다르다: ${allowed.hermesProfile}`,
    );

    step("그 사람의 첫 요청에 자기 에이전트가 하나 생긴다");
    const mine = expectStatus(
      await call(context, "/agents", { token: context.tokens.aunt }),
      200,
      "첫 로그인의 에이전트 목록",
    ).json<AgentView[]>();
    expect(
      mine.length === 1 && mine[0]!.code === NEW_PERSON.hermesProfile,
      `첫 로그인에 에이전트가 하나 생기지 않았다: ${JSON.stringify(mine)}`,
    );
    expect(
      mine[0]!.visibility === "PRIVATE",
      `첫 에이전트가 자기만 보는 것이 아니다: ${mine[0]!.visibility}`,
    );

    step("두 번째 요청에는 에이전트가 늘지 않는다");
    const again = expectStatus(
      await call(context, "/agents", { token: context.tokens.aunt }),
      200,
      "두 번째 요청의 에이전트 목록",
    ).json<AgentView[]>();
    expect(again.length === 1, `두 번째 요청에 에이전트가 늘었다: ${again.length}`);

    step("관리 목록에서 그 사람이 들어온 적 있는 것으로 보인다");
    const list = expectStatus(
      await call(context, "/admin/people", { token: context.tokens.dad }),
      200,
      "사람 목록",
    ).json<PersonView[]>();
    const joined = list.find((person) => person.email === NEW_PERSON.email);
    expect(joined !== undefined, "더한 사람이 목록에 없다");
    expect(joined!.joined, "들어온 적 있는 사람이 아직 없는 것으로 보인다");

    step("사용 중지하면 로그인 판정이 거짓이 된다");
    expectStatus(
      await call(context, `/admin/people/${joined!.id}`, {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: false },
      }),
      200,
      "사용 중지",
    );
    const blocked = expectStatus(
      await call(context, "/signin/allowed", {
        method: "POST",
        token: context.tokens.signin,
        body: { email: NEW_PERSON.email },
      }),
      200,
      "사용 중지한 주소의 로그인 판정",
    ).json<{ allowed: boolean }>();
    expect(!blocked.allowed, "사용 중지한 주소가 로그인 판정을 통과했다");

    step("다시 허용하면 통과한다");
    expectStatus(
      await call(context, `/admin/people/${joined!.id}`, {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: true },
      }),
      200,
      "다시 허용",
    );
    const restored = expectStatus(
      await call(context, "/signin/allowed", {
        method: "POST",
        token: context.tokens.signin,
        body: { email: NEW_PERSON.email },
      }),
      200,
      "다시 허용한 주소의 로그인 판정",
    ).json<{ allowed: boolean }>();
    expect(restored.allowed, "다시 허용한 주소가 로그인 판정을 통과하지 못했다");
  },
};
