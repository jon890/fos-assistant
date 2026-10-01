/**
 * 두 번째 커넥터가 코드 변경 없이 붙는지 전체 흐름으로 본다.
 *
 * <p>대역의 카탈로그에 시험 커넥터 하나가 있을 뿐이다. Control Plane 은 그 선언만 읽고 등록, 확인, 해제까지
 * 돈다. 시험 커넥터는 칸 이름과 env 이름이 다른 서비스와 겹치지 않는다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { DEMO_CONNECTOR, DEMO_TOKEN_BAD, DEMO_TOKEN_OK } from "../fake-hermes.ts";

type FieldView = { key: string; hasOptions: boolean; secret: boolean; required: boolean };
type ConnectorView = { id: string; title: string; fields: FieldView[]; myStatus: string; available: boolean };
type ConnectionView = {
  connectorId: string;
  status: string;
  secretPrefixes: Record<string, string>;
  values: Record<string, string>;
  agentCode: string | null;
  restartRequired: boolean;
};

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;

export const connectorScenario: Scenario = {
  name: "커넥터 연결",

  async run(context) {
    step("카탈로그에 시험 커넥터가 보이고 대시보드 내부 이름은 응답에 없다");
    const catalogResponse = expectStatus(await call(context, "/connectors", { token: context.tokens.dad }), 200, "커넥터 목록");
    const demo = catalogResponse.json<ConnectorView[]>().find((connector) => connector.id === DEMO_CONNECTOR.id);
    expect(demo !== undefined, `커넥터 목록에 ${DEMO_CONNECTOR.id} 가 없다\n${catalogResponse.body}`);
    expect(demo!.title === DEMO_CONNECTOR.title && demo!.available && demo!.myStatus === "DISCONNECTED",
      `시험 커넥터 항목이 다르다\n${catalogResponse.body}`);
    expect(demo!.fields.map((field) => field.key).join() === "token,scope", `칸 순서가 다르다\n${catalogResponse.body}`);
    expect(demo!.fields[0]!.secret && demo!.fields[0]!.required && demo!.fields[1]!.hasOptions,
      `칸의 성질이 선언과 다르다\n${catalogResponse.body}`);
    for (const hidden of ["DEMO_TOKEN", "DEMO_SCOPE", "list_scopes", "verify"]) {
      expect(!catalogResponse.body.includes(hidden), `목록 응답에 ${hidden} 가 새었다`);
    }

    step("선택지를 불러온다");
    const options = expectStatus(
      await call(context, `${CONNECTION}/options/scope`, {
        method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK } },
      }),
      200,
      "선택지 조회",
    );
    expect(JSON.stringify(options.json()) === JSON.stringify([{ value: "a", label: "A" }]), `선택지가 다르다\n${options.body}`);
    expect(!options.body.includes(DEMO_TOKEN_OK), "선택지 응답에 후보 값이 되돌아왔다");

    step("거절된 토큰은 등록되지 않고 아무것도 저장되지 않는다");
    const profilesBefore = context.hermes.profiles().length;
    const requestsBefore = context.hermes.connectorRequests().length;
    const rejected = expectStatus(
      await call(context, CONNECTION, { method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_BAD } } }),
      400,
      "거절된 토큰 등록",
    );
    expect(rejected.json<{ code: string }>().code === "CONNECTOR_CREDENTIAL_REJECTED", `오류 코드가 다르다\n${rejected.body}`);
    expect(!rejected.body.includes(DEMO_TOKEN_BAD), "오류 응답에 토큰이 되돌아왔다");
    const afterRejected = call(context, CONNECTION, { token: context.tokens.dad });
    const rejectedState = expectStatus(await afterRejected, 200, "거절 뒤 상태").json<ConnectionView>();
    expect(rejectedState.status === "DISCONNECTED" && rejectedState.agentCode === null, "거절됐는데 연결이 남았다");
    expect(context.hermes.profiles().length === profilesBefore, "거절됐는데 profile 이 만들어졌다");
    expect(
      context.hermes.connectorRequests().slice(requestsBefore).join() === "call list_scopes",
      `거절 뒤에는 확인 호출만 있어야 한다: ${context.hermes.connectorRequests().slice(requestsBefore).join()}`,
    );

    step("통과한 토큰으로 등록하면 PENDING 이고 대역이 확인, env, 도구 목록, 설치 순으로 받는다");
    const knownProfiles = new Set(context.hermes.profiles());
    const requestsAtRegister = context.hermes.connectorRequests().length;
    const registered = expectStatus(
      await call(context, CONNECTION, {
        method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK, scope: "a" } },
      }),
      200,
      "등록",
    );
    expect(registered.json<ConnectionView>().status === "PENDING", `등록 직후 상태가 PENDING 이 아니다\n${registered.body}`);
    const created = context.hermes.profiles().filter((name) => !knownProfiles.has(name));
    expect(created.length === 1, `전용 profile 이 하나 만들어져야 한다: ${created.join()}`);
    const profile = created[0]!;
    const requests = context.hermes.connectorRequests().slice(requestsAtRegister);
    const at = (line: string): number => requests.indexOf(line);
    const order = [
      at("call list_scopes"),
      at(`env put ${profile} DEMO_TOKEN`),
      at(`env put ${profile} DEMO_SCOPE`),
      requests.lastIndexOf(`toolsets ${profile}`),
      at(`install ${profile} on`),
    ];
    expect(
      order.every((index, position) => index >= 0 && (position === 0 || index > order[position - 1]!)),
      `요청 순서가 확인, env, 도구 목록, 설치가 아니다: ${requests.join(" | ")}`,
    );
    expect(context.hermes.profileEnv(profile).DEMO_TOKEN === DEMO_TOKEN_OK, "토큰이 profile env 에 들어가지 않았다");

    step("연결을 확인하면 READY 이고 비밀은 앞 8자만 보인다");
    const checked = expectStatus(await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인");
    const ready = checked.json<ConnectionView>();
    expect(ready.status === "READY", `READY 가 아니다\n${checked.body}`);
    expect(ready.secretPrefixes.token === DEMO_TOKEN_OK.slice(0, 8), `비밀 앞부분이 다르다\n${checked.body}`);
    expect(ready.values.scope === "a" && ready.values.token === undefined, `칸 값이 다르다\n${checked.body}`);
    expect(context.hermes.connectorRequests().some((line) => line === `probe ${profile}`), "MCP 서버 확인 요청이 없었다");
    const reads = [
      registered.body,
      checked.body,
      (await call(context, CONNECTION, { token: context.tokens.dad })).body,
      (await call(context, "/connectors", { token: context.tokens.dad })).body,
      (await call(context, "/admin/connections", { token: context.tokens.dad })).body,
    ];
    reads.forEach((body, index) => expect(!body.includes(DEMO_TOKEN_OK), `응답 ${index} 에 토큰 원문이 있다`));

    step("다른 사용자는 이 연결을 읽지 못한다");
    const others = expectStatus(await call(context, CONNECTION, { token: context.tokens.kid }), 200, "다른 사용자의 상태").json<ConnectionView>();
    expect(
      others.status === "DISCONNECTED" && others.agentCode === null && Object.keys(others.secretPrefixes).length === 0
        && Object.keys(others.values).length === 0,
      `다른 사용자에게 연결이 보인다: ${JSON.stringify(others)}`,
    );
    expectStatus(await call(context, "/admin/connections", { token: context.tokens.kid }), 403, "일반 사용자의 관리자 목록");
    expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.kid }), 400, "다른 사용자의 해제");

    step("해제하면 DISCONNECTED 이고 대역의 env 와 설치가 지워진다");
    const disconnected = expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
    expect(disconnected.json<ConnectionView>().status === "DISCONNECTED", `해제 뒤 상태가 다르다\n${disconnected.body}`);
    expect(context.hermes.connectorRequests().includes(`install ${profile} off`), "설치 해제 요청이 없었다");
    expect(context.hermes.profileEnv(profile).DEMO_TOKEN === undefined, "해제했는데 토큰 env 가 남았다");
  },
};
