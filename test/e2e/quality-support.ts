/**
 * 배포 뒤 확인과 묶음 평가 시나리오가 함께 쓰는 준비다.
 *
 * <p>실제 Hermes 는 profile 마다 Control Plane MCP 에 연결해 두고 keepalive 로 `tools/list` 를 되풀이해 부른다. 실행기는 그 요청이 없으면 실패한
 * 문항을 평가 환경 오류로 적는다(docs/features/agent-quality/design.md 의 「평가 환경 오류」). 가짜 Hermes 는 연결을 들고 있지 않으므로, 품질 실패를
 * 보는 시나리오는 시작 전에 평가 profile 의 토큰으로 `tools/list` 를 한 번 불러 연결이 살아 있는 상태를 만든다.
 */
import { call, expect, expectStatus, type Context } from "./harness.ts";

/** 평가 profile 의 Control Plane MCP 연결이 살아 있는 상태를 만든다. 평가 사용자를 준비한 뒤에 부른다. */
export async function connectEvaluationMcp(context: Context): Promise<void> {
  const status = expectStatus(await call(context, "/admin/quality/evaluation-user", { token: context.tokens.dad }), 200, "평가 사용자 상태")
    .json<{ hermesProfile: string | null }>();
  const profile = status.hermesProfile;
  if (profile === null) throw new Error("평가 사용자의 profile 이 없다");
  const token = context.hermes.profileEnv(profile).MCP_FOS_ASSISTANT_API_KEY;
  if (token === undefined || token === "") throw new Error(`평가 profile 에 MCP 토큰이 없다: ${profile}`);
  const response = await fetch(`${context.api.replace(/\/api\/v1$/, "")}/mcp`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
  });
  expect(response.ok, `평가 profile 로 MCP 도구 목록을 읽지 못했다: ${response.status}`);
}
