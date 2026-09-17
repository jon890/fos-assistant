/** 현재 사용자의 정보와 역할을 검사한다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type MeView = {
  id: number;
  email: string;
  displayName: string;
  role: "ADMIN" | "MEMBER";
};

export const meScenario: Scenario = {
  name: "현재 사용자",

  async run(context) {
    step("첫 사용자는 admin 역할과 자기 정보를 받는다");
    const admin = expectStatus(
      await call(context, "/me", { token: context.tokens.dad }),
      200,
      "admin 현재 사용자",
    ).json<MeView>();
    expect(admin.email === "dad@example.com", "admin 메일 주소가 다르다");
    expect(admin.displayName === "dad@example.com", "admin 표시 이름이 다르다");
    expect(admin.role === "ADMIN", "첫 사용자의 역할이 ADMIN 이 아니다");

    step("다음 사용자는 member 역할과 자기 정보를 받는다");
    const member = expectStatus(
      await call(context, "/me", { token: context.tokens.kid }),
      200,
      "member 현재 사용자",
    ).json<MeView>();
    expect(member.email === "kid@example.com", "member 메일 주소가 다르다");
    expect(member.displayName === "kid@example.com", "member 표시 이름이 다르다");
    expect(member.role === "MEMBER", "다음 사용자의 역할이 MEMBER 가 아니다");

    step("토큰이 없으면 현재 사용자를 읽지 못한다");
    expectStatus(await call(context, "/me"), 401, "인증 없는 현재 사용자 조회");
  },
};
