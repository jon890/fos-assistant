/** 에이전트 toolset의 등급과 비공개 제약을 전체 흐름에서 본다. */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { FAKE_DASHBOARD_TOKEN } from "../fake-hermes.ts";

export const AGENT_TOOLS_PROFILE = "kid-tools-profile";

type ToolsetView = { name: string; enabled: boolean; editable: boolean; requiresPrivate: boolean };
type ToolsetsView = { toolsets: ToolsetView[]; unclassifiedEnabled: string[] };

export const agentToolsScenario: Scenario = {
  name: "에이전트 도구",

  async run(context) {
    step("관리자가 다른 사용자의 개인 에이전트를 등록한다");
    expectStatus(
      await call(context, "/admin/agents", {
        method: "POST",
        token: context.tokens.dad,
        body: {
          code: "kid-tools",
          name: "Kid tools",
          hermesProfile: AGENT_TOOLS_PROFILE,
          apiBaseUrl: `${context.hermesBaseUrl}/p/${AGENT_TOOLS_PROFILE}`,
          costMode: "SUBSCRIPTION",
          credentialScope: "SHARED_HOUSEHOLD",
          visibility: "PRIVATE",
          ownerEmail: "kid@example.com",
        },
      }),
      200,
      "도구 검사 에이전트 등록",
    );

    step("대시보드는 query와 본문의 profile이 다르면 설정을 거절한다");
    const mismatchedProfile = await fetch(`${context.hermesBaseUrl}/api/config?profile=other-profile`, {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${FAKE_DASHBOARD_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        profile: AGENT_TOOLS_PROFILE,
        config: {
          platform_toolsets: { api_server: ["web", "fos-assistant"] },
        },
      }),
    });
    expect(mismatchedProfile.status === 400, "profile이 다른 설정 요청이 거절되지 않았다");

    step("API 실행의 도구 목록에는 MCP 서버 이름이 없다");
    const builtinToolsets = await fetch(`${context.hermesBaseUrl}/p/${AGENT_TOOLS_PROFILE}/v1/toolsets`, {
      headers: { Authorization: `Bearer ${context.hermesProfileKey}` },
    });
    expect(builtinToolsets.status === 200, "API 실행 도구 목록을 읽지 못했다");
    const builtinNames = (await builtinToolsets.json() as { data: { name: string }[] }).data.map((entry) => entry.name);
    expect(!builtinNames.includes("fos-assistant"), "MCP 서버가 내장 도구 목록에 들어 있다");

    step("주인은 자기 등급의 web만 켤 수 있다");
    const initial = expectStatus(
      await call(context, "/agents/kid-tools/tools", { token: context.tokens.kid }),
      200,
      "도구 목록 조회",
    ).json<ToolsetsView>().toolsets;
    expect(initial.some((tool) => tool.name === "web" && tool.editable), "web이 주인에게 편집 가능하지 않다");
    expect(initial.some((tool) => tool.name === "terminal" && !tool.editable && tool.requiresPrivate), "terminal 등급이 다르다");
    context.hermes.dropNextAppliedToolset("web");
    const notApplied = expectStatus(
      await call(context, "/agents/kid-tools/tools", {
        method: "PUT", token: context.tokens.kid, body: { enabled: ["web"] },
      }),
      502,
      "profile에서 막힌 web 켜기",
    ).json<{ code: string; missingToolsets?: string[] }>();
    expect(notApplied.code === "AGENT_TOOLS_NOT_APPLIED", "적용 누락 오류 코드가 다르다");
    expect(notApplied.missingToolsets?.join(",") === "web", "적용되지 않은 toolset 이름이 없다");
    const enabled = expectStatus(
      await call(context, "/agents/kid-tools/tools", {
        method: "PUT",
        token: context.tokens.kid,
        body: { enabled: ["web"] },
      }),
      200,
      "web 켜기",
    ).json<ToolsetsView>().toolsets;
    expect(enabled.some((tool) => tool.name === "web" && tool.enabled), "web이 켜진 목록으로 오지 않는다");

    step("주인이 terminal을 켜려 하면 거절한다");
    const refused = expectStatus(
      await call(context, "/agents/kid-tools/tools", {
        method: "PUT",
        token: context.tokens.kid,
        body: { enabled: ["web", "terminal"] },
      }),
      403,
      "주인의 terminal 켜기",
    );
    const refusedBody = refused.json<{ code: string; missingToolsets?: string[] }>();
    expect(refusedBody.code === "FORBIDDEN", `기대한 오류 코드가 아니다: ${refused.body}`);
    expect(refusedBody.missingToolsets === undefined, "일반 오류에 missingToolsets가 실렸다");

    step("관리자는 개인 에이전트에 terminal을 켤 수 있다");
    expectStatus(
      await call(context, "/admin/agents/kid-tools/tools", {
        method: "PUT",
        token: context.tokens.dad,
        body: { enabled: ["web", "terminal"] },
      }),
      200,
      "관리자의 terminal 켜기",
    );

    step("terminal이 켜진 에이전트는 그룹 공개로 바꿀 수 없다");
    const groupRefused = expectStatus(
      await call(context, "/admin/agents/kid-tools", {
        method: "PATCH",
        token: context.tokens.dad,
        body: { enabled: true, visibility: "GROUP", ownerEmail: null },
      }),
      409,
      "terminal이 켜진 에이전트의 그룹 공개",
    );
    expect(
      groupRefused.json<{ code: string }>().code === "AGENT_TOOLS_REQUIRE_PRIVATE",
      `기대한 오류 코드가 아니다: ${groupRefused.body}`,
    );

    step("도구 저장과 그룹 공개가 겹쳐도 private-only 도구가 그룹에 남지 않는다");
    expectStatus(
      await call(context, "/admin/agents/kid-tools/tools", {
        method: "PUT", token: context.tokens.dad, body: { enabled: ["web"] },
      }),
      200,
      "경합 전 terminal 끄기",
    );
    context.hermes.holdNextConfig();
    const toolWrite = call(context, "/admin/agents/kid-tools/tools", {
      method: "PUT", token: context.tokens.dad, body: { enabled: ["web", "terminal"] },
    });
    await context.hermes.waitForHeldConfig();
    const groupWrite = call(context, "/admin/agents/kid-tools", {
      method: "PATCH", token: context.tokens.dad,
      body: { enabled: true, visibility: "GROUP", ownerEmail: null },
    });
    let groupResult;
    try {
      groupResult = await Promise.race([
        groupWrite,
        new Promise<never>((_, reject) => setTimeout(() => reject(new Error("그룹 공개 잠금 대기가 끝나지 않았다")), 8_000)),
      ]);
    } finally {
      context.hermes.releaseHeldConfig();
    }
    const toolResult = await toolWrite;
    expect(toolResult.status === 200, `도구 저장이 실패했다: ${toolResult.body}`);
    expect(groupResult.status === 409, `그룹 공개가 잠금 대기 뒤 거절되지 않았다: ${groupResult.body}`);
    expect(groupResult.json<{ code: string }>().code === "AGENT_BUSY", `기대한 오류 코드가 아니다: ${groupResult.body}`);
  },
};
