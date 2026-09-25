/** 에이전트 공개 범위와 관리 권한을 검사한다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";

type AgentView = { code: string; visibility: string };

export const agentsScenario: Scenario = {
  name: "에이전트 공개 범위",

  async run(context) {
    step("남의 개인 에이전트는 사용할 수 있는 목록에 없다");
    const privateList = expectStatus(
      await call(context, "/agents", { token: context.tokens.kid }),
      200,
      "member 에이전트 목록",
    ).json<AgentView[]>();
    expect(privateList.every((agent) => agent.code !== "dad"), "남의 개인 에이전트가 목록에 보인다");

    step("관리자가 가족 공개로 바꾸면 다른 사용자도 에이전트를 볼 수 있다");
    expectStatus(
      await call(context, "/admin/agents/dad", {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: true, visibility: "FAMILY", ownerEmail: null },
      }),
      200,
      "가족 공개 변경",
    );
    const familyList = expectStatus(
      await call(context, "/agents", { token: context.tokens.kid }),
      200,
      "가족 공개 에이전트 목록",
    ).json<AgentView[]>();
    expect(familyList.some((agent) => agent.code === "dad"), "가족 공개 에이전트가 목록에 없다");

    step("member는 에이전트 공개 범위를 바꾸지 못한다");
    expectStatus(
      await call(context, "/admin/agents/dad", {
        method: "PATCH",
        token: context.tokens.kid,
        body: { enabled: false, visibility: "FAMILY", ownerEmail: null },
      }),
      403,
      "member의 에이전트 변경",
    );

    step("대화 전에 개인 범위로 되돌린다");
    expectStatus(
      await call(context, "/admin/agents/dad", {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: true, visibility: "PRIVATE", ownerEmail: "dad@example.com" },
      }),
      200,
      "개인 범위 복원",
    );

    step("사용 중지한 에이전트는 실행할 수 없다");
    expectStatus(
      await call(context, "/admin/agents/dad", {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: false, visibility: "PRIVATE", ownerEmail: "dad@example.com" },
      }),
      200,
      "에이전트 사용 중지",
    );
    const disabled = expectStatus(
      await call(context, "/chat/messages", {
        method: "POST",
        token: context.tokens.dad,
        body: { text: "실행되면 안 돼", agentCode: "dad" },
      }),
      409,
      "사용 중지 에이전트 실행",
    );
    expect(disabled.json<{ code: string }>().code === "AGENT_DISABLED", "사용 중지 오류 코드가 다르다");
    expectStatus(
      await call(context, "/admin/agents/dad", {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: true, visibility: "PRIVATE", ownerEmail: "dad@example.com" },
      }),
      200,
      "에이전트 사용 복원",
    );
  },
};
