import { type FakeHermesState } from "./state.ts";
import { createBindings, catalogOf, fakeConnector, verifyAnswer, SERVER_TOOLS } from "./connectors.ts";
import { createContentSnapshotRoutes } from "./content-snapshot-routes.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import {
  CONNECTOR_CATALOG_PATH,
  CONNECTOR_CALL_PATH,
  CONNECTOR_EXECUTE_PATH,
  CONNECTORS_PATH,
  CONNECTOR_VAULT_PATH,
  VAULT_NAME,
  MCP_SERVER_TEST_PATH,
} from "./runtime-fixtures.ts";
import { send, readBody } from "./lifecycle.ts";
/** 커넥터 카탈로그, 도구 호출, 설치와 보관 파일 경로를 처리한다. */
export function createConnectorRoutes(state: FakeHermesState, { bindingInstall, vaultValuesValid }: ReturnType<typeof createBindings>) {
  const snapshotRoutes = createContentSnapshotRoutes(state);
  return async (request: IncomingMessage, response: ServerResponse, path: string, queryProfile: string | null): Promise<boolean> => {
    if (request.method === "GET" && path === CONNECTOR_CATALOG_PATH) {
      send(response, 200, catalogOf(state));
      return true;
    }

    if (await snapshotRoutes(request, response, path)) return true;

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
      const bound = body.profile !== undefined && state.boundConnectors.get(body.profile)?.has(connectorId) === true;
      if (fakeConnector(connectorId) === undefined || !bound) {
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
      const toolsets = state.apiServerToolsets.get(queryProfile) ?? [];
      const states = catalogOf(state).map((connector) => {
        const bound = state.boundConnectors.get(queryProfile)?.has(connector.id) === true;
        const filled = connector.fields.every((field) => !field.required || env[field.env] !== undefined);
        const configured = filled && bound && toolsets.includes(connector.mcp_server);
        return {
          plugin: connector.id,
          enabled: bound,
          configured,
          mode: "bind",
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
      bindingInstall(response, body.profile, body.plugin, body.enabled, body.bind, body.sandbox_owner);
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

    const probeMatch = MCP_SERVER_TEST_PATH.exec(path);
    if (request.method === "POST" && probeMatch !== null) {
      // 그 profile 에 붙인 커넥터의 서버만 시험한다.
      const connector = catalogOf(state).find((candidate) => candidate.mcp_server === probeMatch[1]);
      if (connector === undefined || queryProfile === null
          || !state.boundConnectors.get(queryProfile)?.has(connector.id)) {
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
