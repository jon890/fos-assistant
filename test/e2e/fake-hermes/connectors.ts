import { type FakeConnectorField, type FakeConnector, type FakeHermesState } from "./state.ts";
import { type ServerResponse } from "node:http";
import { send } from "./lifecycle.ts";
import { SANDBOX_OWNER_PATTERN, CONTROL_PLANE_MCP } from "./runtime-fixtures.ts";
import { join } from "node:path";

/**
 * 대역이 카탈로그로 내는 시험 커넥터다. 선언 모양은 plugin 이 읽는 `connector.json` 과 같고, 카탈로그 응답에는
 * 거기에 `mcp_server` 가 더해진다. 칸의 이름과 env 이름을 어느 서비스의 것과도 다르게 둔다.
 */
export const DEMO_CONNECTOR = {
  id: "demo-notes",
  schema: 2,
  title: "검사용 메모",
  description: "검사에서만 쓰는 커넥터입니다.",
  fields: [
    {
      key: "token", env: "DEMO_TOKEN", label: "토큰", description: "검사용 토큰입니다.",
      secret: true, required: true, pattern: "^demo_[a-z]+_[0-9]{10}$",
    },
    {
      key: "scope", env: "DEMO_SCOPE", label: "범위", required: false,
      options: { tool: "list_scopes", items: "scopes", value: "id", label: "name", auto_select_single: true },
    },
  ],
  verify: { tool: "list_scopes" },
  mcp_server: "demo",
  // manifest 가 선언한 내장 toolset 이다. 설치가 도구 목록에서 서버 이름 다음에 둔다.
  toolsets: [] as string[],
  attachments: false,
  // 도구마다의 정책이다. 확인 도구이자 선택지 도구인 `list_scopes` 는 읽기 전용이고 승인이 없다.
  // `grant` 는 대시보드 plugin 이 기본값을 채워 내는 값이다. 승인이 `required` 인 도구만 참이다.
  // `outbound` 도 기본값을 채워 낸다. 밖으로 나간다고 선언한 도구만 참이다.
  // `identifiers` 는 선언한 도구만 낸다. 칸이 없으면 Control Plane 이 빈 목록으로 읽는다.
  tools: {
    list_scopes: { risk: "READ", approval: "none", grant: false, outbound: false },
    write_note: { risk: "WRITE", approval: "required", title: "메모 쓰기", grant: true, outbound: false },
    purge_notes: { risk: "DESTRUCTIVE", approval: "always", grant: false, outbound: false },
    // 상시 허락을 닫고 `note_id` 를 식별자로 선언한 도구다(ADR-089). Gmail 의 필터 지우기와 같은 모양이다.
    delete_note: {
      risk: "WRITE", approval: "required", title: "메모 지우기", grant: false, outbound: false, identifiers: ["note_id"],
    },
  },
};

export const DEMO_TOKEN_OK = "demo_ok_0123456789";

export const DEMO_TOKEN_BAD = "demo_bad_0123456789";

/**
 * 대역이 카탈로그로 내는 둘째 시험 커넥터다. 입력 칸이 없어 연결에 값을 받지 않고 확인 도구는 늘 통과한다.
 *
 * <p>한 에이전트에 연결 둘을 붙였을 때 도구마다 제 연결로 판정하는지 보는 데 쓴다. 서버 이름이 첫째 커넥터와 다르다.
 */
export const AGENDA_CONNECTOR = {
  id: "demo-agenda",
  schema: 2,
  title: "검사용 일정",
  description: "검사에서만 쓰는 입력 칸 없는 커넥터입니다.",
  fields: [] as FakeConnectorField[],
  verify: { tool: "list_events" },
  mcp_server: "agenda",
  toolsets: [] as string[],
  attachments: false,
  tools: {
    list_events: { risk: "READ", approval: "none", grant: false, outbound: false },
    add_event: { risk: "WRITE", approval: "required", title: "일정 쓰기", grant: true, outbound: false },
  },
};

/** 카탈로그에 내는 커넥터들이다. 옛 설치(`isolated`)와 보관 파일 가져오기는 첫째 커넥터만 흉내 낸다. */
export const FAKE_CONNECTORS: readonly FakeConnector[] = [DEMO_CONNECTOR, AGENDA_CONNECTOR];

/** MCP 서버가 실제로 내는 도구다. `hidden_tool` 은 manifest 가 선언하지 않은 도구다. */
export const SERVER_TOOLS: Record<string, string[]> = {
  [DEMO_CONNECTOR.mcp_server]: ["list_scopes", "write_note", "purge_notes", "hidden_tool"],
  [AGENDA_CONNECTOR.mcp_server]: ["list_events", "add_event"],
};

export function fakeConnector(id: string | undefined): FakeConnector | undefined {
  return FAKE_CONNECTORS.find((connector) => connector.id === id);
}

/** 확인 도구의 답이다. 첫째 커넥터는 토큰을 보고, 칸이 없는 둘째 커넥터는 늘 통과한다. */
export function verifyAnswer(connector: FakeConnector, values: Record<string, string> | undefined): unknown {
  if (connector.id === AGENDA_CONNECTOR.id) return { ok: true, result: { events: [] } };
  return values?.token === DEMO_TOKEN_OK
    ? { ok: true, result: { scopes: [{ id: "a", name: "A" }] } }
    : { ok: false, error: "credential_rejected" };
}

/** 커넥터의 보관 값과 바인딩 설치를 흉내 낸다. */
export function createBindings(state: FakeHermesState) {
  /** 그 profile 의 `.env` 다. 대시보드로 만든 profile 은 `profiles` 의 것을 쓴다. */
  const envOf = (profile: string): Record<string, string> => {
    const managed = state.profiles.get(profile);
    if (managed !== undefined) return managed;
    const host = state.hostEnv.get(profile) ?? {};
    state.hostEnv.set(profile, host);
    return host;
  };
  /** 그 profile 에 바인딩 설치한 커넥터의 MCP 서버 이름이다. 도구 목록을 쓰는 요청이 이 이름을 모두 실어야 한다. */
  const boundServers = (profile: string): string[] =>
    [...(state.boundConnectors.get(profile)?.keys() ?? [])].flatMap((id) => fakeConnector(id)?.mcp_server ?? []);
  /** 그 커넥터의 칸 선언과 맞는 값인가. 모르는 키, 필수 칸 누락, `pattern` 위반, 두 줄 이상인 값은 받지 않는다. */
  const vaultValuesValid = (connector: FakeConnector, values: unknown): values is Record<string, string> => {
    if (typeof values !== "object" || values === null || Array.isArray(values)) return false;
    const given = values as Record<string, unknown>;
    const declared = new Set(connector.fields.map((field) => field.key));
    if (Object.entries(given).some(([key, value]) =>
      !declared.has(key) || typeof value !== "string" || value === "" || value.includes("\n"))) {
      return false;
    }
    return connector.fields.every((field) => {
      const value = given[field.key] as string | undefined;
      if (value === undefined) return !field.required;
      return field.pattern === undefined || new RegExp(field.pattern).test(value);
    });
  };

  /**
   * `PUT /api/connectors` 의 바인딩 설치와 그 떼기다. 받는 조건과 바꾸는 것은 `docs/backend/connector-install.md` 의
   * 「바인딩 설치」 를 따른다.
   *
   * <p>붙이기는 보관 파일의 값을 그 profile 의 `.env` 에 쓰고 서버 이름을 API 도구 목록에 더한다. 있던 이름은 그대로 둔다.
   * 바뀐 것이 있으면 `restart_required` 가 참이다. 떠 있는 profile 에 더한 MCP 서버는 재시작해야 보이기 때문이다. 떼기는 그
   * 이름만 빼고 env 를 지우며 재시작을 요구하지 않는다. 스킬 복사는 흉내 내지 않는다. 시험 커넥터에 스킬이 없다.
   */
  const bindingInstall = (
    response: ServerResponse,
    profile: string,
    plugin: string,
    enabled: boolean,
    bind: { vault?: unknown } | undefined,
    sandboxOwner: unknown,
  ) => {
    if (!state.profiles.has(profile) && state.keys[profile] === undefined) {
      send(response, 401, { reason: "not_marked" });
      return;
    }
    const answer = (changed: boolean, restartRequired: boolean) => send(response, 200, {
      profile, plugin, enabled, changed, restart_required: restartRequired, plugin_updated: false,
    });
    const bound = state.boundConnectors.get(profile) ?? new Map<string, string>();
    const toolsets = state.apiServerToolsets.get(profile);
    const connector = fakeConnector(plugin);
    if (!enabled) {
      if (bind !== undefined) {
        send(response, 400, { error: "invalid connector request" });
        return;
      }
      state.connectorRequests.push(`unbind ${profile}`);
      // 카탈로그에 없는 커넥터도 실제 대시보드는 소유 기록만 보고 뗀다. 이 대역은 그 기록만 지운다.
      if (connector === undefined) {
        answer(bound.delete(plugin), false);
        return;
      }
      // 그 profile 에 붙지 않은 커넥터는 뗄 것이 없다. 실제 대시보드처럼 아무것도 바꾸지 않고 바뀐 것 없이 답한다.
      if (!bound.delete(plugin)) {
        answer(false, false);
        return;
      }
      const env = envOf(profile);
      for (const field of connector.fields) delete env[field.env];
      if (toolsets !== undefined) {
        state.apiServerToolsets.set(profile, toolsets.filter((name) => name !== connector.mcp_server));
      }
      answer(true, false);
      return;
    }
    // 바인딩 항목이 있는 profile 은 옛 설치를 받지 않는다.
    if (bind === undefined) {
      send(response, 409, { error: "this profile has bound connectors" });
      return;
    }
    // Control Plane 은 바인딩 설치에 그 에이전트의 `sandbox_owner` 를 늘 싣는다(ADR-20261007 connector-owner-attachments). 빠지거나 모양이 틀리면 거절한다.
    if (typeof sandboxOwner !== "string" || !SANDBOX_OWNER_PATTERN.test(sandboxOwner)) {
      send(response, 400, { error: "sandbox_owner is required for a binding install" });
      return;
    }
    const stored = typeof bind.vault === "string" ? state.vaults.get(bind.vault) : undefined;
    if (connector === undefined || stored === undefined || stored.connector !== plugin) {
      send(response, 400, { error: "no such vault for this connector" });
      return;
    }
    // 도구 목록이 없거나 Control Plane MCP 가 없는 목록, 어느 커넥터든 옛 설치가 있는 profile 은 파일을 하나도 바꾸지 않고 거절한다.
    if (toolsets === undefined || !toolsets.includes(CONTROL_PLANE_MCP)
        || (state.installedConnectors.get(profile)?.size ?? 0) > 0) {
      send(response, 409, { error: "the profile conflicts with this connector" });
      return;
    }
    // 정책 hook 이 꺼진 profile 에는 새로 붙이지 않는다. 이미 붙은 커넥터를 다시 설치하는 것은 받고 hook 상태로 PENDING 에 남는다.
    if (!bound.has(plugin) && state.policyHookOff.has(profile)) {
      send(response, 409, { error: "the policy hook plugin is not enabled" });
      return;
    }
    const env = envOf(profile);
    let changed = bound.get(plugin) !== bind.vault;
    for (const field of connector.fields) {
      const value = stored.values[field.key];
      if (env[field.env] !== value) changed = true;
      if (value === undefined) delete env[field.env];
      else env[field.env] = value;
    }
    const next = toolsets.filter((name) => name !== "no_mcp");
    if (!next.includes(connector.mcp_server)) next.push(connector.mcp_server);
    if (next.join() !== toolsets.join()) changed = true;
    state.apiServerToolsets.set(profile, next);
    bound.set(plugin, bind.vault as string);
    state.boundConnectors.set(profile, bound);
    state.policyHookInstalled.add(profile);
    state.connectorRequests.push(`bind ${profile}`);
    answer(changed, changed);
  };
  return { envOf, boundServers, vaultValuesValid, bindingInstall };
}
