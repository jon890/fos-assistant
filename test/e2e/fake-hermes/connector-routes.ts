import { type FakeHermesState } from "./state.ts";
import { createBindings, FAKE_CONNECTORS, fakeConnector, verifyAnswer, DEMO_CONNECTOR, SERVER_TOOLS } from "./connectors.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import {
  CONNECTOR_CATALOG_PATH,
  CONNECTOR_CALL_PATH,
  CONNECTOR_EXECUTE_PATH,
  CONNECTORS_PATH,
  CONNECTOR_VAULT_PATH,
  VAULT_NAME,
  CONNECTOR_VAULT_IMPORT_PATH,
  MCP_SERVER_TEST_PATH,
} from "./runtime-fixtures.ts";
import { send, readBody } from "./lifecycle.ts";
import { join } from "node:path";
/** 커넥터 카탈로그, 도구 호출, 설치와 보관 파일 경로를 처리한다. */
export function createConnectorRoutes(state: FakeHermesState, { bindingInstall, vaultValuesValid }: ReturnType<typeof createBindings>) {
  return async (request: IncomingMessage, response: ServerResponse, path: string, queryProfile: string | null): Promise<boolean> => {
    if (request.method === "GET" && path === CONNECTOR_CATALOG_PATH) {
      send(response, 200, FAKE_CONNECTORS);
      return true;
    }

    const callMatch = CONNECTOR_CALL_PATH.exec(path);
    if (request.method === "POST" && callMatch !== null) {
      const connector = fakeConnector(callMatch[1]);
      if (connector === undefined) {
        send(response, 404, { error: "no such connector" });
        return true;
      }
      const body = JSON.parse((await readBody(request)) || "{}") as {
        tool?: string; values?: Record<string, string>; vault?: string;
      };
      // 칸 값은 본문의 `values` 나 보관 파일 가운데 정확히 하나에서 온다.
      if ((body.values === undefined) === (body.vault === undefined)) {
        send(response, 400, { error: "values or vault is required" });
        return true;
      }
      const stored = body.vault === undefined ? undefined : state.vaults.get(body.vault);
      if (body.vault !== undefined && stored?.connector !== callMatch[1]) {
        send(response, 400, { error: "no such vault for this connector" });
        return true;
      }
      const values = stored?.values ?? body.values;
      if (body.tool !== connector.verify.tool) {
        send(response, 200, { ok: false, error: "invalid_input" });
        return true;
      }
      state.connectorRequests.push(`call ${body.tool}`);
      send(response, 200, verifyAnswer(connector, values));
      return true;
    }

    // 대시보드의 실행 경로다. 승인 여부를 다시 보지 않고 받은 호출을 한 번 실행한 것으로 친다.
    const executeMatch = CONNECTOR_EXECUTE_PATH.exec(path);
    if (request.method === "POST" && executeMatch !== null) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string; hermes_tool?: string; args?: unknown;
      };
      const connectorId = executeMatch[1]!;
      const installed = body.profile !== undefined
        && (state.installedConnectors.get(body.profile)?.has(connectorId) === true
          || state.boundConnectors.get(body.profile)?.has(connectorId) === true);
      if (fakeConnector(connectorId) === undefined || !installed) {
        send(response, 404, { error: "no such connector" });
        return true;
      }
      if (typeof body.hermes_tool !== "string" || typeof body.args !== "object" || body.args === null) {
        send(response, 400, { error: "invalid request" });
        return true;
      }
      state.connectorToolCalls.push({
        profile: body.profile!, hermesTool: body.hermes_tool, argsJson: JSON.stringify(body.args), via: "execute",
      });
      send(response, 200, { ok: true, result: { saved: true } });
      return true;
    }

    if (request.method === "GET" && path === CONNECTORS_PATH) {
      // 대시보드로 만든 profile 은 관리 표식이, 미리 심어 둔 profile 은 운영자가 둔 커넥터 표식이 있는 것으로 친다.
      if (queryProfile === null || (!state.profiles.has(queryProfile) && state.keys[queryProfile] === undefined)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const env = state.profiles.get(queryProfile) ?? state.hostEnv.get(queryProfile) ?? {};
      const installed = state.installedConnectors.get(queryProfile) ?? new Set<string>();
      const toolsets = state.apiServerToolsets.get(queryProfile) ?? [];
      const states = FAKE_CONNECTORS.map((connector) => {
        const bound = state.boundConnectors.get(queryProfile)?.has(connector.id) === true;
        const filled = connector.fields.every((field) => !field.required || env[field.env] !== undefined);
        // 옛 설치는 도구 목록이 설치가 쓰는 목록과 같을 때만 configured 다. 다른 내장 도구나 Control Plane MCP 가 남으면 아니다.
        // 바인딩 설치는 서버 이름이 목록에 있으면 된다. Control Plane MCP 와 다른 도구가 함께 있어도 된다.
        const configured = filled && (bound
          ? toolsets.includes(connector.mcp_server)
          : toolsets.join() === [connector.mcp_server, ...connector.toolsets].join());
        return {
          plugin: connector.id,
          enabled: bound || installed.has(connector.id),
          configured,
          mode: bound ? "bind" : "isolated",
        };
      });
      send(response, 200, {
        profile: queryProfile,
        policy_hook: state.policyHookInstalled.has(queryProfile) && !state.policyHookOff.has(queryProfile),
        connectors: states,
      });
      return true;
    }

    if (request.method === "PUT" && path === CONNECTORS_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string; plugin?: string; enabled?: unknown; bind?: { vault?: unknown }; sandbox_owner?: unknown;
      };
      if (body.profile === undefined || typeof body.plugin !== "string" || typeof body.enabled !== "boolean") {
        send(response, 400, { error: "invalid connector request" });
        return true;
      }
      const boundHere = state.boundConnectors.get(body.profile);
      if (body.bind !== undefined || boundHere?.has(body.plugin) === true) {
        bindingInstall(response, body.profile, body.plugin, body.enabled, body.bind, body.sandbox_owner);
        return true;
      }
      // 어느 커넥터든 바인딩 항목이 있는 profile 은 옛 설치를 받지 않는다.
      if (body.enabled && (boundHere?.size ?? 0) > 0) {
        send(response, 409, { error: "this profile has bound connectors" });
        return true;
      }
      if (!state.profiles.has(body.profile)) {
        // 커넥터 표식만 있는 profile 은 옛 설치를 받지 않는다. 붙은 것이 없는 떼기는 끌 것이 없으므로 바뀐 것 없이 성공한다.
        if (state.keys[body.profile] !== undefined && !body.enabled) {
          send(response, 200, {
            profile: body.profile, plugin: body.plugin, enabled: false, changed: false, restart_required: false,
            plugin_updated: false,
          });
        } else {
          send(response, 400, { error: "invalid connector request" });
        }
        return true;
      }
      if (body.plugin !== DEMO_CONNECTOR.id) {
        // 모르는 plugin 은 켜지 못한다. 끄기는 끌 것이 없으므로 바뀐 것 없이 성공한다.
        if (body.enabled) send(response, 400, { error: "invalid connector request" });
        else {
          send(response, 200, {
            profile: body.profile, plugin: body.plugin, enabled: false, changed: false, restart_required: false,
            plugin_updated: false,
          });
        }
        return true;
      }
      state.connectorRequests.push(`install ${body.profile} ${body.enabled ? "on" : "off"}`);
      const installed = state.installedConnectors.get(body.profile) ?? new Set<string>();
      const changed = installed.has(body.plugin) !== body.enabled;
      if (body.enabled) {
        installed.add(body.plugin);
        // 설치는 그 profile 의 정책 hook 을 지금 판으로 맞춘다.
        state.policyHookInstalled.add(body.profile);
      } else {
        installed.delete(body.plugin);
      }
      state.installedConnectors.set(body.profile, installed);
      // 설치는 그 profile 의 API 도구 목록을 커넥터의 MCP 서버 이름과 선언한 toolset 으로 다시 쓰고,
      // 해제는 MCP 가 없는 목록으로 쓴다.
      state.apiServerToolsets.set(
        body.profile,
        body.enabled ? [DEMO_CONNECTOR.mcp_server, ...DEMO_CONNECTOR.toolsets] : ["no_mcp"],
      );
      send(response, 200, {
        profile: body.profile, plugin: body.plugin, enabled: body.enabled, changed, restart_required: false,
        plugin_updated: false,
      });
      return true;
    }

    // 보관 파일 경로다. 값과 이름은 응답과 요청 기록에 싣지 않는다.
    if (request.method === "PUT" && path === CONNECTOR_VAULT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { vault?: unknown; connector?: unknown; values?: unknown };
      const connector = typeof body.connector === "string" ? fakeConnector(body.connector) : undefined;
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault) || connector === undefined
          || !vaultValuesValid(connector, body.values)) {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      if (state.vaults.has(body.vault) && state.vaults.get(body.vault)!.connector !== connector.id) {
        send(response, 409, { error: "the vault belongs to another connector" });
        return true;
      }
      state.vaults.set(body.vault, { connector: connector.id, values: { ...body.values } });
      state.connectorRequests.push("vault put");
      send(response, 200, { ok: true });
      return true;
    }

    if (request.method === "DELETE" && path === CONNECTOR_VAULT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { vault?: unknown };
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault)) {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      state.connectorRequests.push("vault delete");
      send(response, 200, { changed: state.vaults.delete(body.vault) });
      return true;
    }

    if (request.method === "POST" && path === CONNECTOR_VAULT_IMPORT_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        vault?: unknown; connector?: unknown; profile?: unknown;
      };
      if (typeof body.vault !== "string" || !VAULT_NAME.test(body.vault) || body.connector !== DEMO_CONNECTOR.id
          || typeof body.profile !== "string") {
        send(response, 400, { error: "invalid vault request" });
        return true;
      }
      // 옛 설치는 관리 표식이 있는 profile 에만 있다.
      const env = state.profiles.get(body.profile);
      if (env === undefined) {
        send(response, state.keys[body.profile] === undefined ? 404 : 401, { error: "no managed profile" });
        return true;
      }
      if (!state.installedConnectors.get(body.profile)?.has(DEMO_CONNECTOR.id)) {
        send(response, 404, { error: "the connector is not installed in this profile" });
        return true;
      }
      if (state.vaults.has(body.vault) && state.vaults.get(body.vault)!.connector !== DEMO_CONNECTOR.id) {
        send(response, 409, { error: "the vault belongs to another connector" });
        return true;
      }
      const values: Record<string, string> = {};
      for (const field of DEMO_CONNECTOR.fields) {
        const value = env[field.env];
        if (value !== undefined && value !== "") values[field.key] = value;
      }
      if (!vaultValuesValid(DEMO_CONNECTOR, values)) {
        send(response, 400, { error: "a required field is empty" });
        return true;
      }
      state.vaults.set(body.vault, { connector: DEMO_CONNECTOR.id, values });
      state.connectorRequests.push(`vault import ${body.profile}`);
      send(response, 200, { ok: true });
      return true;
    }

    const probeMatch = MCP_SERVER_TEST_PATH.exec(path);
    if (request.method === "POST" && probeMatch !== null) {
      // 그 profile 에 설치하거나 붙인 커넥터의 서버만 시험한다.
      const connector = FAKE_CONNECTORS.find((candidate) => candidate.mcp_server === probeMatch[1]);
      if (connector === undefined || queryProfile === null
          || (!state.installedConnectors.get(queryProfile)?.has(connector.id)
            && !state.boundConnectors.get(queryProfile)?.has(connector.id))) {
        send(response, 404, { error: "no such mcp server" });
        return true;
      }
      state.connectorRequests.push(`probe ${queryProfile}`);
      send(response, 200, { ok: true, tools: (SERVER_TOOLS[connector.mcp_server] ?? []).map((name) => ({ name })) });
      return true;
    }

    return false;
  };
}
