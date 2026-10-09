import { type FakeHermesState } from "./state.ts";
import { createBindings } from "./connectors.ts";
import { createLifecycle, send, readBody } from "./lifecycle.ts";
import { createConnectorRoutes } from "./connector-routes.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import {
  SOUL_PATH,
  MODEL_DEFAULTS_PATH,
  SESSION_PROVIDER_PATH,
  PROFILE_PATH,
  PROFILES_PATH,
  ENV_PATH,
  TOOLSET_CATALOG_PATH,
  CONFIG_PATH,
  SKILLS_PATH,
  SKILL_TOGGLE_PATH,
  CONNECTORS_PATH,
  CONNECTOR_CATALOG_PATH,
  CONNECTOR_VAULT_PATH,
  CONNECTOR_VAULT_IMPORT_PATH,
  CONNECTOR_CALL_PATH,
  CONNECTOR_EXECUTE_PATH,
  MCP_SERVER_TEST_PATH,
  DEFAULT_RUNTIME,
  PLUGIN_TEMPLATE_TOOLSETS,
  API_KEY_ENV_NAME,
  TOOLSET_CATALOG,
  SANDBOX_OWNER_PATTERN,
  CONTROL_PLANE_MCP,
  SANDBOX_TOOLSETS,
  SKILL_VERSION_DIR,
  DEFAULT_API_SERVER_TOOLSETS,
} from "./runtime-fixtures.ts";
import { BUILTIN_SKILLS, readPublishedSkills } from "./scenario-fixtures.ts";
import { lstatSync } from "node:fs";
/** 대시보드 인증을 확인한 뒤 원래 순서대로 경로를 처리한다. */
export function createDashboard(state: FakeHermesState, bindings: ReturnType<typeof createBindings>, lifecycle: ReturnType<typeof createLifecycle>, skillRoot?: string) {
  const connectorRoutes = createConnectorRoutes(state, bindings);
  const configRoutes = createConfigRoutes(state, bindings, skillRoot);
  const profileRoutes = createProfileRoutes(state);
  const { dashboardAuthorized } = lifecycle;
  return async (request: IncomingMessage, response: ServerResponse, path: string, queryProfile: string | null): Promise<boolean> => {

    const soulMatch = SOUL_PATH.exec(path);
    const modelDefaultsMatch = MODEL_DEFAULTS_PATH.exec(path);
    const sessionProviderMatch = SESSION_PROVIDER_PATH.exec(path);
    const profileMatch = PROFILE_PATH.exec(path);
    const isDashboardPath = path === PROFILES_PATH || path === ENV_PATH || path === TOOLSET_CATALOG_PATH
      || path === CONFIG_PATH || path === SKILLS_PATH || path === SKILL_TOGGLE_PATH || profileMatch !== null
      || path === CONNECTORS_PATH || path === CONNECTOR_CATALOG_PATH
      || path === CONNECTOR_VAULT_PATH || path === CONNECTOR_VAULT_IMPORT_PATH
      || CONNECTOR_CALL_PATH.test(path) || CONNECTOR_EXECUTE_PATH.test(path) || MCP_SERVER_TEST_PATH.test(path);
    if (!isDashboardPath) return false;

    if (!dashboardAuthorized(request)) {
      send(response, 401, { reason: "no_token" });
      return true;
    }

    if (await connectorRoutes(request, response, path, queryProfile)) return true;
    if (await configRoutes(request, response, path, queryProfile)) return true;
    if (await profileRoutes(request, response, path, queryProfile)) return true;
    send(response, 404, { error: "not found" });
    return true;

  };
}
/** profile, 성격과 환경 변수 경로를 처리한다. */
export function createProfileRoutes(state: FakeHermesState) {
  return async (request: IncomingMessage, response: ServerResponse, path: string, queryProfile: string | null): Promise<boolean> => {
    const soulMatch = SOUL_PATH.exec(path);
    const modelDefaultsMatch = MODEL_DEFAULTS_PATH.exec(path);
    const sessionProviderMatch = SESSION_PROVIDER_PATH.exec(path);
    const profileMatch = PROFILE_PATH.exec(path);
    if (modelDefaultsMatch !== null && request.method === "GET") {
      send(response, 200, {
        provider: DEFAULT_RUNTIME.provider,
        model: DEFAULT_RUNTIME.model,
        reasoningEffort: "medium",
      });
      return true;
    }

    // `PROFILE_PATH` 의 `.+` 가 이 경로도 함께 먹으므로 그 분기보다 앞에서 처리한다.
    if (request.method === "GET" && sessionProviderMatch !== null) {
      const child = state.childUsages.get(decodeURIComponent(sessionProviderMatch[2]!));
      if (child === undefined || child.profile !== decodeURIComponent(sessionProviderMatch[1]!)) {
        send(response, 404, { detail: "없는 session 이다" });
        return true;
      }
      send(response, 200, { provider: child.provider ?? null, model: child.model ?? "example-fast" });
      return true;
    }

    if (soulMatch !== null) {
      const name = decodeURIComponent(soulMatch[1]!);
      if (request.method === "GET") {
        const content = state.souls.get(name);
        const payload = { content: content ?? "", exists: content !== undefined };
        if (state.holdNextSoul) {
          state.holdNextSoul = false;
          state.heldSoul = { response, payload };
          return true;
        }
        send(response, 200, payload);
        return true;
      }
      if (request.method === "PUT") {
        const body = JSON.parse((await readBody(request)) || "{}") as { content?: string };
        state.souls.set(name, body.content ?? "");
        // 실제 대시보드는 쓴 본문을 되돌려주지 않고 `{"ok": true}` 만 준다.
        send(response, 200, { ok: true });
        return true;
      }
    }

    if (request.method === "POST" && path === PROFILES_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { name?: string };
      const name = body.name ?? "";
      if (name.length === 0) {
        send(response, 400, { error: "name is required" });
        return true;
      }
      if (state.profiles.has(name)) {
        send(response, 409, { error: "profile already exists" });
        return true;
      }
      state.profiles.set(name, {});
      state.apiServerToolsets.set(name, [...PLUGIN_TEMPLATE_TOOLSETS]);
      send(response, 200, { name });
      return true;
    }

    if (request.method === "PUT" && path === ENV_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        key?: string;
        value?: string;
      };
      const env = body.profile === undefined ? undefined : state.profiles.get(body.profile);
      if (env === undefined || body.key === undefined || body.value === undefined) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      env[body.key] = body.value;
      if (state.connectorEnvNames.has(body.key)) {
        // 커넥터 칸은 응답이 재시작 필요 여부도 담는다. 값은 적지 않는다.
        state.connectorRequests.push(`env put ${body.profile} ${body.key}`);
        send(response, 200, { profile: body.profile, key: body.key, restart_required: false });
        return true;
      }
      // 이 칸으로 들어온 값이 그 profile 의 key 가 된다. 대역이 스스로 만들면 Control Plane 이 key
      // 파일에 쓴 값과 어긋나 그 profile 의 실행이 401 을 받는다.
      if (body.key === API_KEY_ENV_NAME) state.keys[body.profile!] = body.value;
      send(response, 200, { profile: body.profile, key: body.key });
      return true;
    }

    if (request.method === "DELETE" && path === ENV_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as { profile?: string; key?: string };
      const env = body.profile === undefined ? undefined : state.profiles.get(body.profile);
      if (env === undefined || body.key === undefined || !state.connectorEnvNames.has(body.key)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      state.connectorRequests.push(`env delete ${body.profile} ${body.key}`);
      delete env[body.key];
      send(response, 200, { profile: body.profile, key: body.key, restart_required: false });
      return true;
    }

    if (request.method === "DELETE" && profileMatch !== null) {
      const name = decodeURIComponent(profileMatch[1]!);
      if (!state.profiles.delete(name)) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      delete state.keys[name];
      // 붙인 커넥터의 소유 기록과 `.env` 는 profile 디렉터리에 있어 함께 사라진다.
      state.boundConnectors.delete(name);
      state.hostEnv.delete(name);
      send(response, 200, { name });
      return true;
    }

    return false;
  };
}

/** 도구 목록과 스킬 게시 설정 경로를 처리한다. */
export function createConfigRoutes(state: FakeHermesState, { boundServers }: ReturnType<typeof createBindings>, skillRoot?: string) {
  return async (request: IncomingMessage, response: ServerResponse, path: string, queryProfile: string | null): Promise<boolean> => {
    if (request.method === "GET" && path === TOOLSET_CATALOG_PATH) {
      send(response, 200, TOOLSET_CATALOG.map((toolset) => ({ ...toolset, enabled: false })));
      return true;
    }

    if (request.method === "GET" && path === SKILLS_PATH) {
      if (queryProfile === null) {
        send(response, 400, { error: "profile query is required" });
        return true;
      }
      if (!state.keys[queryProfile]) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const disabled = state.disabledSkills.get(queryProfile) ?? new Set<string>();
      // 실제 응답의 모양이다. `enabled` 는 전역 `skills.disabled` 만 반영한다.
      send(response, 200, [
        ...BUILTIN_SKILLS.map((skill) => ({ ...skill, category: "builtin", provenance: "bundled" })),
        ...readPublishedSkills(state.externalDirs.get(queryProfile) ?? []).map((skill) => ({
          ...skill, category: "agent", provenance: "agent",
        })),
      ].map((skill) => ({ ...skill, enabled: !disabled.has(skill.name), usage: 0 })));
      return true;
    }

    if (request.method === "PUT" && path === SKILL_TOGGLE_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        name?: string;
        enabled?: unknown;
      };
      if (body.profile === undefined || body.name === undefined || typeof body.enabled !== "boolean") {
        send(response, 400, { error: "profile, name and enabled are required" });
        return true;
      }
      if (!state.keys[body.profile]) {
        send(response, 404, { error: "no such profile" });
        return true;
      }
      const disabled = state.disabledSkills.get(body.profile) ?? new Set<string>();
      if (body.enabled) disabled.delete(body.name);
      else disabled.add(body.name);
      state.disabledSkills.set(body.profile, disabled);
      send(response, 200, { ok: true, name: body.name, enabled: body.enabled });
      return true;
    }

    if (request.method === "PUT" && path === CONFIG_PATH) {
      const body = JSON.parse((await readBody(request)) || "{}") as {
        profile?: string;
        config?: {
          platform_toolsets?: { api_server?: unknown };
          skills?: { external_dirs?: unknown };
        };
        sandbox_owner?: unknown;
        require_sandbox?: unknown;
      };
      const configKeys = Object.keys(body.config ?? {});
      // 도구와 스킬 게시만 받는다. 둘을 한 본문에 함께 둘 수 있고, 그 밖의 키는 거절한다.
      // 최상위에는 profile 과 config 가 있어야 하고 실행 공간 주인 sandbox_owner 와 require_sandbox 만 더 둘 수 있다.
      const bodyKeys = Object.keys(body);
      const exactKeys = bodyKeys.includes("profile") && bodyKeys.includes("config")
        && bodyKeys.every((key) => key === "profile" || key === "config" || key === "sandbox_owner"
          || key === "require_sandbox")
        && configKeys.length >= 1
        && configKeys.every((key) => key === "platform_toolsets" || key === "skills");
      if (body.profile === undefined || queryProfile !== null && queryProfile !== body.profile
          || !state.keys[body.profile] || !exactKeys) {
        send(response, 400, { error: "invalid configuration" });
        return true;
      }
      // plugin 처럼 참 거짓 값만 받는다. 대역에는 미등록 profile 의 local 실행이 없어 값은 거절 여부를 바꾸지 않는다.
      if (body.require_sandbox !== undefined && typeof body.require_sandbox !== "boolean") {
        send(response, 400, { error: "require_sandbox must be a boolean" });
        return true;
      }
      // plugin 처럼 칸이 있으면 셸 도구가 없어도 모양을 본다.
      if (body.sandbox_owner !== undefined
          && (typeof body.sandbox_owner !== "string" || !SANDBOX_OWNER_PATTERN.test(body.sandbox_owner))) {
        send(response, 400, { error: "sandbox_owner has an invalid shape" });
        return true;
      }
      const profile = body.profile;
      let nextToolsets: string[] | undefined;
      if (configKeys.includes("platform_toolsets")) {
        const toolsets = body.config?.platform_toolsets?.api_server;
        const bound = boundServers(profile);
        const validNames = new Set([...TOOLSET_CATALOG.map((entry) => entry.name), CONTROL_PLANE_MCP, ...bound]);
        if (Object.keys(body.config?.platform_toolsets ?? {}).length !== 1 || !Array.isArray(toolsets)
            || !toolsets.includes(CONTROL_PLANE_MCP)
            || !toolsets.every((name) => typeof name === "string" && validNames.has(name))) {
          send(response, 400, { error: "invalid toolset configuration" });
          return true;
        }
        nextToolsets = toolsets as string[];
        // plugin 처럼 셸 도구가 있을 때만 주인을 읽는다. 없거나 모양이 틀리면 설정을 바꾸지 않고 거절한다.
        if (nextToolsets.some((name) => SANDBOX_TOOLSETS.includes(name))) {
          if (typeof body.sandbox_owner !== "string" || !SANDBOX_OWNER_PATTERN.test(body.sandbox_owner)) {
            send(response, 400, { error: "sandbox_owner is required for shell toolsets" });
            return true;
          }
          if (state.sandboxUnavailable) {
            send(response, 409, { detail: "the isolated shell workspace is not configured", code: "sandbox_unavailable" });
            return true;
          }
        }
        // 처리기가 목록을 통째로 바꾸므로 붙은 커넥터의 서버 이름이 빠진 목록은 조용히 지우지 않고 거절한다.
        // plugin 처럼 실행 공간 검사 뒤에 본다. 둘 다 409 라 순서가 다르면 Control Plane 이 다른 오류로 읽는다.
        if (!bound.every((name) => toolsets.includes(name))) {
          send(response, 409, { error: "연결된 커넥터의 도구 이름이 빠졌다" });
          return true;
        }
      }
      let nextDirs: string[] | undefined;
      if (configKeys.includes("skills")) {
        const dirs = body.config?.skills?.external_dirs;
        // 게시 거절 규칙은 hermes/plugins/dashboard-profile-api/README.md 의 경로 표를 따른다. 경로 형식, 다른 profile 의 prefix, 둘 이상,
        // 심볼릭 링크, 없는 디렉터리, skills 도구가 꺼진 채 게시가 모두 400 이다.
        if (Object.keys(body.config?.skills ?? {}).length !== 1 || !Array.isArray(dirs) || dirs.length > 1
            || !dirs.every((dir) => typeof dir === "string")) {
          send(response, 400, { error: "invalid skills configuration" });
          return true;
        }
        for (const dir of dirs as string[]) {
          const match = SKILL_VERSION_DIR.exec(dir);
          if (match === null || match[1] !== profile || (skillRoot !== undefined && !dir.startsWith(`${skillRoot}/`))) {
            send(response, 400, { error: "external_dirs path is not this profile's skill directory" });
            return true;
          }
          let stat;
          try {
            stat = lstatSync(dir);
          } catch {
            send(response, 400, { error: "external_dirs path does not exist" });
            return true;
          }
          if (stat.isSymbolicLink() || !stat.isDirectory()) {
            send(response, 400, { error: "external_dirs path is not a plain directory" });
            return true;
          }
        }
        const effectiveToolsets = nextToolsets ?? state.apiServerToolsets.get(profile) ?? DEFAULT_API_SERVER_TOOLSETS;
        if (dirs.length > 0 && !effectiveToolsets.includes("skills")) {
          send(response, 400, { error: "the skills toolset is off for this profile" });
          return true;
        }
        nextDirs = dirs as string[];
      }
      if (state.holdNextConfig) {
        state.holdNextConfig = false;
        state.heldConfigWaiter?.();
        await new Promise<void>((done) => { state.releaseConfig = done; });
        state.releaseConfig = undefined;
      }
      state.lastSandboxOwner = typeof body.sandbox_owner === "string" ? body.sandbox_owner : null;
      if (nextToolsets !== undefined) {
        state.connectorRequests.push(`toolsets ${profile}`);
        state.apiServerToolsets.set(profile, nextToolsets.filter((name) => name !== state.droppedToolset));
        state.droppedToolset = undefined;
      }
      if (nextDirs !== undefined) state.externalDirs.set(profile, nextDirs);
      send(response, 200, { ok: true });
      return true;
    }

    return false;
  };
}
