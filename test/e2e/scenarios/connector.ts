/**
 * 두 번째 커넥터가 코드 변경 없이 붙는지 전체 흐름으로 본다.
 *
 * <p>대역의 카탈로그에는 시험 커넥터만 있다. Control Plane 은 그 선언만 읽고 등록, 확인, 해제까지 돈다. 시험 커넥터는
 * 칸 이름과 env 이름이 다른 서비스와 겹치지 않는다. 연결은 계정 하나이고 에이전트를 만들지 않는다(ADR-083). 값은 대역의
 * 보관 파일에만 가고, 에이전트에 붙이는 흐름은 연결 붙이기 시나리오가 본다.
 */
import { call, expect, expectStatus, step, type Scenario } from "../harness.ts";
import { DEMO_CONNECTOR, DEMO_TOKEN_BAD, DEMO_TOKEN_OK } from "../fake-hermes.ts";
import type { BoundAgentView, ConnectionView } from "../connector-support.ts";

type FieldView = { key: string; hasOptions: boolean; secret: boolean; required: boolean };
type ToolView = { name: string; title: string | null; risk: string; approval: string; grant: boolean };
type ConnectorView = {
  id: string; title: string; fields: FieldView[]; tools: ToolView[]; myStatus: string; available: boolean;
  bindings: BoundAgentView[];
};

const CONNECTION = `/connections/${DEMO_CONNECTOR.id}`;

export const connectorScenario: Scenario = {
  name: "커넥터 연결",

  async run(context) {
    step("카탈로그에 시험 커넥터가 보이고 대시보드 내부 이름은 응답에 없다");
    const catalogResponse = expectStatus(await call(context, "/connectors", { token: context.tokens.dad }), 200, "커넥터 목록");
    const demo = catalogResponse.json<ConnectorView[]>().find((connector) => connector.id === DEMO_CONNECTOR.id);
    expect(demo !== undefined, `커넥터 목록에 ${DEMO_CONNECTOR.id} 가 없다\n${catalogResponse.body}`);
    expect(
      demo!.title === DEMO_CONNECTOR.title && demo!.available && demo!.myStatus === "DISCONNECTED"
        && demo!.bindings.length === 0,
      `시험 커넥터 항목이 다르다\n${catalogResponse.body}`,
    );
    expect(demo!.fields.map((field) => field.key).join() === "token,scope", `칸 순서가 다르다\n${catalogResponse.body}`);
    expect(demo!.fields[0]!.secret && demo!.fields[0]!.required && demo!.fields[1]!.hasOptions,
      `칸의 성질이 선언과 다르다\n${catalogResponse.body}`);
    // 도구 이름은 이제 도구 정책으로 나온다. env 이름과 확인 도구 선언은 그대로 숨긴다.
    for (const hidden of ["DEMO_TOKEN", "DEMO_SCOPE", "verify"]) {
      expect(!catalogResponse.body.includes(hidden), `목록 응답에 ${hidden} 가 새었다`);
    }
    const writeNote = demo!.tools.find((tool) => tool.name === "write_note");
    expect(
      writeNote !== undefined && writeNote.risk === "WRITE" && writeNote.approval === "REQUIRED"
        && writeNote.title === DEMO_CONNECTOR.tools.write_note.title && writeNote.grant === true,
      `write_note 의 도구 정책이 선언과 다르다\n${catalogResponse.body}`,
    );
    // 승인이 없거나 늘 승인을 받는 도구에는 상시 허락을 줄 수 없다.
    expect(
      demo!.tools.filter((tool) => tool.name !== "write_note").every((tool) => tool.grant === false),
      `write_note 밖의 도구에 상시 허락을 줄 수 있다고 나왔다\n${catalogResponse.body}`,
    );
    expect(
      demo!.tools.map((tool) => tool.name).join() === Object.keys(DEMO_CONNECTOR.tools).join(),
      `도구 목록이 선언과 다르다\n${catalogResponse.body}`,
    );

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
    const rejectedState = expectStatus(await call(context, CONNECTION, { token: context.tokens.dad }), 200, "거절 뒤 상태")
      .json<ConnectionView>();
    expect(
      rejectedState.status === "DISCONNECTED" && rejectedState.bindings.length === 0,
      `거절됐는데 연결이 남았다: ${JSON.stringify(rejectedState)}`,
    );
    expect(context.hermes.profiles().length === profilesBefore, "거절됐는데 profile 이 만들어졌다");
    expect(
      context.hermes.connectorRequests().slice(requestsBefore).join() === "call list_scopes",
      `거절 뒤에는 확인 호출만 있어야 한다: ${context.hermes.connectorRequests().slice(requestsBefore).join()}`,
    );

    step("통과한 토큰으로 등록하면 READY 이고 붙은 에이전트가 없으며 대역은 확인과 보관 파일 쓰기만 받는다");
    const requestsAtRegister = context.hermes.connectorRequests().length;
    const registered = expectStatus(
      await call(context, CONNECTION, { method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK } } }),
      200,
      "등록",
    );
    const connected = registered.json<ConnectionView & { agentCode?: unknown }>();
    expect(
      connected.status === "READY" && connected.bindings.length === 0 && !("agentCode" in connected),
      `등록 응답이 붙은 에이전트 없는 READY 가 아니다\n${registered.body}`,
    );
    expect(
      connected.secretPrefixes.token === DEMO_TOKEN_OK.slice(0, 4) && Object.keys(connected.values).length === 0,
      `등록한 칸 값이 다르다\n${registered.body}`,
    );
    expect(
      context.hermes.connectorRequests().slice(requestsAtRegister).join(" | ") === "call list_scopes | vault put",
      `등록 동안 확인과 보관 파일 쓰기 한 번만 받아야 한다: ${context.hermes.connectorRequests().slice(requestsAtRegister).join(" | ")}`,
    );
    expect(context.hermes.profiles().length === profilesBefore, "등록이 profile 을 만들었다");

    step("값을 바꾸면 보관 파일을 한 번 다시 쓰고 선택 칸이 반영된다");
    const requestsAtReplace = context.hermes.connectorRequests().length;
    const replaced = expectStatus(
      await call(context, CONNECTION, {
        method: "POST", token: context.tokens.dad, body: { values: { token: DEMO_TOKEN_OK, scope: "a" } },
      }),
      200,
      "값 교체",
    );
    const replacedView = replaced.json<ConnectionView>();
    expect(
      replacedView.status === "READY" && replacedView.values.scope === "a" && replacedView.bindings.length === 0,
      `값을 바꾼 응답이 다르다\n${replaced.body}`,
    );
    expect(
      context.hermes.connectorRequests().slice(requestsAtReplace).join(" | ") === "call list_scopes | vault put",
      `값 교체 동안 확인과 보관 파일 쓰기 한 번만 받아야 한다: ${context.hermes.connectorRequests().slice(requestsAtReplace).join(" | ")}`,
    );

    step("연결을 확인하면 보관 파일의 값으로 확인 도구를 부르고 READY 이며 16자 이상인 비밀은 앞 4자만 보인다");
    const requestsAtCheck = context.hermes.connectorRequests().length;
    const checked = expectStatus(await call(context, `${CONNECTION}/check`, { method: "POST", token: context.tokens.dad }), 200, "연결 확인");
    const ready = checked.json<ConnectionView>();
    expect(ready.status === "READY" && ready.checkedAt !== null, `READY 가 아니다\n${checked.body}`);
    expect(
      context.hermes.connectorRequests().slice(requestsAtCheck).join(" | ") === "call list_scopes",
      `붙은 에이전트가 없는 연결 확인은 확인 도구만 불러야 한다: ${context.hermes.connectorRequests().slice(requestsAtCheck).join(" | ")}`,
    );
    expect(DEMO_TOKEN_OK.length >= 16, "검사용 토큰이 앞부분을 저장하는 길이보다 짧다");
    expect(ready.secretPrefixes.token === DEMO_TOKEN_OK.slice(0, 4), `비밀 앞부분이 다르다\n${checked.body}`);
    expect(ready.values.scope === "a" && ready.values.token === undefined, `칸 값이 다르다\n${checked.body}`);
    const catalogAfter = expectStatus(await call(context, "/connectors", { token: context.tokens.dad }), 200, "등록 뒤 커넥터 목록");
    const listed = catalogAfter.json<ConnectorView[]>().find((connector) => connector.id === DEMO_CONNECTOR.id);
    expect(
      listed?.myStatus === "READY" && listed.bindings.length === 0,
      `등록 뒤 목록의 시험 커넥터가 붙은 에이전트 없는 READY 가 아니다\n${catalogAfter.body}`,
    );
    const reads = [
      registered.body,
      replaced.body,
      checked.body,
      catalogAfter.body,
      (await call(context, CONNECTION, { token: context.tokens.dad })).body,
      (await call(context, "/admin/connections", { token: context.tokens.dad })).body,
    ];
    reads.forEach((body, index) => expect(!body.includes(DEMO_TOKEN_OK), `응답 ${index} 에 토큰 원문이 있다`));
    expect(
      !context.hermes.connectorRequests().some((line) => line.includes(DEMO_TOKEN_OK)),
      "대역의 요청 기록에 토큰 원문이 있다",
    );

    step("다른 사용자는 이 연결을 읽지 못한다");
    const others = expectStatus(await call(context, CONNECTION, { token: context.tokens.kid }), 200, "다른 사용자의 상태").json<ConnectionView>();
    expect(
      others.status === "DISCONNECTED" && others.bindings.length === 0 && Object.keys(others.secretPrefixes).length === 0
        && Object.keys(others.values).length === 0,
      `다른 사용자에게 연결이 보인다: ${JSON.stringify(others)}`,
    );
    expectStatus(await call(context, "/admin/connections", { token: context.tokens.kid }), 403, "일반 사용자의 관리자 목록");
    expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.kid }), 400, "다른 사용자의 해제");

    step("해제하면 DISCONNECTED 이고 대역의 보관 파일이 지워진다");
    const requestsAtDisconnect = context.hermes.connectorRequests().length;
    const disconnected = expectStatus(await call(context, CONNECTION, { method: "DELETE", token: context.tokens.dad }), 200, "해제");
    const disconnectedView = disconnected.json<ConnectionView>();
    expect(
      disconnectedView.status === "DISCONNECTED" && disconnectedView.bindings.length === 0,
      `해제 뒤 상태가 다르다\n${disconnected.body}`,
    );
    expect(
      context.hermes.connectorRequests().slice(requestsAtDisconnect).join(" | ") === "vault delete",
      `붙은 에이전트가 없는 해제는 보관 파일만 지워야 한다: ${context.hermes.connectorRequests().slice(requestsAtDisconnect).join(" | ")}`,
    );
  },
};
