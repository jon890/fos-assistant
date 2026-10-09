/**
 * 홈서버 없이 돌리기 위한 Hermes Runs API 대역이다.
 *
 * <p>Control Plane 이 실제로 부르는 것만 구현한다. profile 경로로 실행을 제출하고, 그 실행의 상태와
 * 토큰 수를 돌려준다. profile 마다 bearer key 도 검사한다. 라우팅을 잘못하면 조용히 성공하는 대신
 * 여기서 401 이 나게 하기 위해서다.
 */
import { type FakeHermes, createFakeHermesState, type ProactiveScript } from "./fake-hermes/state.ts";
import { createBindings } from "./fake-hermes/connectors.ts";
import { createMcp } from "./fake-hermes/mcp.ts";
import { createLifecycle } from "./fake-hermes/lifecycle.ts";
import { createDashboard } from "./fake-hermes/dashboard.ts";
import { createControlRoutes } from "./fake-hermes/control-routes.ts";
import { createRuntimeRoutes, createRunRoutes } from "./fake-hermes/run-routes.ts";
import { createEventRoutes } from "./fake-hermes/event-routes.ts";
import { type Server, createServer } from "node:http";
import { RUN_EVENTS_PATH } from "./fake-hermes/runtime-fixtures.ts";
import { randomUUID } from "node:crypto";

/**
 * fake Hermes 를 띄우고 그 주소를 돌려준다.
 *
 * @param profileKeys profile 이름과 그 profile 의 API server key
 * @param label 이 대역을 다른 대역과 구분하는 이름. 실행의 답에 그대로 실린다. 주소를 옮기는 검사가
 *     답이 어느 대역에서 왔는지 보는 데 쓴다
 * @param skillRoot Control Plane 이 스킬 버전 디렉터리를 쓰는 루트. 주면 그 아래 경로만 게시로 받는다.
 *     Hermes 쪽 루트와 같은 경로여야 대역이 게시된 `SKILL.md` 를 읽을 수 있다
 */
export function startFakeHermes(
  profileKeys: Record<string, string>,
  label?: string,
  initialApiServerToolsets: Record<string, string[]> = {},
  skillRoot?: string,
): Promise<FakeHermes> {
  const state = createFakeHermesState(profileKeys, label, initialApiServerToolsets);
  const bindings = createBindings(state);
  const mcp = createMcp(state);
  const lifecycle = createLifecycle(state);
  const { judgeConnectorCalls, readMemoryViaMcp } = mcp;
  const { releaseHeldRun, releaseHeldSoul, releaseLongActivity } = lifecycle;
  const handleDashboard = createDashboard(state, bindings, lifecycle, skillRoot);
  const handleControls = createControlRoutes(state, lifecycle);
  const handleRuntime = createRuntimeRoutes(state, lifecycle);
  const handleEvents = createEventRoutes(state, lifecycle);
  const handleRuns = createRunRoutes(state, lifecycle, mcp);
  const server: Server = createServer((request, response) => {
    void (async () => {

      const requestUrl = new URL(request.url ?? "/", "http://fake-hermes.test");
      const path = requestUrl.pathname;

      await handleControls(request, response, path);
      if (response.writableEnded) return;
      if (await handleDashboard(request, response, path, requestUrl.searchParams.get("profile"))) return;

      if (request.method === "GET") {
        await handleRuntime(request, response, path);
        if (response.writableEnded) return;
        // 사건 스트림은 열린 응답도 처리 완료다.
        if (RUN_EVENTS_PATH.test(path)) {
          await handleEvents(request, response, path);
          return;
        }
      }
      await handleRuns(request, response, path);
    })();
  });

  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("fake Hermes 의 포트를 알 수 없다"));
        return;
      }
      resolve({
        baseUrl: `http://127.0.0.1:${address.port}`,
        lastSubmittedInstructions: () => state.lastSubmittedInstructions,
        lastSubmittedInput: () => state.lastSubmittedInput,
        lastSubmittedImages: () => state.lastSubmittedImages,
        modelOptionsCalls: () => state.modelOptionsCalls,
        lastSubmittedRuntime: () => state.lastSubmittedRuntime,
        blockProvider: (provider: string) => state.blockedProviders.add(provider),
        clearBlockedProviders: () => state.blockedProviders.clear(),
        busy: () => {
          state.busy = true;
        },
        clearBusy: () => {
          state.busy = false;
        },
        setReadinessOutage: (outage) => {
          state.readinessOutage = outage;
        },
        submitCount: () => state.submitCount,
        profiles: () => [...state.profiles.keys()],
        profileEnv: (name: string) => ({ ...(state.profiles.get(name) ?? state.hostEnv.get(name) ?? {}) }),
        soulOf: (name: string) => state.souls.get(name),
        skillDirsOf: (name: string) => [...(state.externalDirs.get(name) ?? [])],
        apiServerToolsetsOf: (name: string) => {
          const toolsets = state.apiServerToolsets.get(name);
          return toolsets === undefined ? undefined : [...toolsets];
        },
        connectorRequests: () => [...state.connectorRequests],
        setPolicyHook: (profile: string, active: boolean) => {
          if (active) state.policyHookOff.delete(profile);
          else state.policyHookOff.add(profile);
        },
        setConnectorPolicy: (endpoint: string) => {
          state.connectorPolicyEndpoint = endpoint;
        },
        connectorToolCalls: () => state.connectorToolCalls.map((entry) => ({ ...entry })),
        callConnectorTools: (profile: string, sessionId: string, lines: readonly string[], token: string) => {
          state.directConnectorCalls += 1;
          return judgeConnectorCalls(profile, token, sessionId, lines, `connector-direct-${state.directConnectorCalls}`);
        },
        boundConnectorsOf: (profile: string) => [...(state.boundConnectors.get(profile)?.keys() ?? [])],
        holdNextRun: () => {
          state.holdNextRun = true;
          state.heldRunReady = new Promise<void>((done) => {
            state.heldRunWaiter = done;
          });
        },
        slowRuns: (ms: number | undefined) => {
          state.slowRunMs = ms;
        },
        runConcurrency: () => ({
          maxTotal: state.maxTotalConcurrency,
          maxByProfile: Object.fromEntries(state.maxProfileConcurrency),
        }),
        resetRunConcurrency: () => {
          state.maxTotalConcurrency = 0;
          state.maxProfileConcurrency.clear();
        },
        waitForHeldRun: () => state.heldRunReady ?? Promise.reject(new Error("유지할 실행을 먼저 지정해야 한다")),
        releaseHeldRun: () => {
          state.holdNextRun = false;
          const releasedRunId = releaseHeldRun();
          if (releasedRunId === undefined) return;
          const run = state.runs.get(releasedRunId);
          if (run !== undefined) run.status = "completed";
        },
        forgetRun: (runId: string) => {
          state.runs.delete(runId);
        },
        waitForRunEvents: (runId: string) =>
          state.eventsOpened.has(runId)
            ? Promise.resolve()
            : new Promise<void>((done) => {
                state.eventsWaiters.set(runId, [...(state.eventsWaiters.get(runId) ?? []), done]);
              }),
        heldRun: () => {
          const run = state.heldRunId === undefined ? undefined : state.runs.get(state.heldRunId);
          return run === undefined ? undefined : { runId: run.run_id, sessionId: run.session_id };
        },
        releaseLongActivity,
        holdNextSoul: () => {
          state.holdNextSoul = true;
        },
        releaseHeldSoul,
        stoppedRuns: () => [...state.stoppedRuns],
        holdNextConfig: () => {
          state.holdNextConfig = true;
          state.heldConfigReady = new Promise<void>((done) => { state.heldConfigWaiter = done; });
        },
        waitForHeldConfig: () => state.heldConfigReady ?? Promise.reject(new Error("유지할 설정을 먼저 지정해야 한다")),
        releaseHeldConfig: () => state.releaseConfig?.(),
        dropNextAppliedToolset: (name) => { state.droppedToolset = name; },
        setArtifactWriteMcp: (endpoint: string, token: string) => {
          state.artifactWriteMcp = { endpoint, token };
        },
        setMemoryReadMcp: (endpoint: string, token: string) => {
          state.memoryReadMcp = { endpoint, token };
        },
        setMemoryRememberCall: (input, args) => {
          state.memoryRememberCalls.set(input, args);
        },
        setOutsideToolRun: (input) => {
          state.outsideToolInputs.add(input);
        },
        subagentRegistrations: () => [...state.subagentRegistrations],
        readMemoryAsSubagent: (childSessionId, memoryId) => {
          const registered = state.subagentRegistrations.find((entry) => entry.childSessionId === childSessionId);
          if (registered === undefined) return Promise.reject(new Error(`등록한 적 없는 자식 session 이다: ${childSessionId}`));
          return readMemoryViaMcp(memoryId, childSessionId, registered.rootSessionId);
        },
        readMemoryAsUnregisteredSubagent: (rootSessionId, memoryId) =>
          readMemoryViaMcp(memoryId, `native-${randomUUID()}`, rootSessionId),
        setProactiveScript: (script: ProactiveScript) => {
          state.proactiveScript = { ...script, tools: [...(script.tools ?? [])] };
          state.proactiveGate = script.waitBeforeEvents === true
            ? new Promise<void>((done) => {
                state.openProactiveGate = done;
              })
            : undefined;
          if (script.hold === true) {
            state.heldRunReady = new Promise<void>((done) => {
              state.heldRunWaiter = done;
            });
          }
        },
        releaseProactiveEvents: () => state.openProactiveGate?.(),
        proactiveInputs: () => state.proactiveInputs.map((entry) => ({ ...entry })),
        close: () =>
          new Promise<void>((done) => {
            state.holdNextRun = false;
            releaseHeldRun();
            releaseLongActivity();
            state.openProactiveGate?.();
            state.holdNextSoul = false;
            state.heldSoul = undefined;
            server.closeAllConnections();
            server.close(() => done());
          }),
      });
    });
  });
}

export { TOOL_DETAIL_SECRETS, TOOL_DETAIL_SAMPLE, ASK_GUIDE_HEADER, ARTIFACT_PROBE, ARTIFACT_SAME_NAME_PROBE, ARTIFACT_WRITE_PROBE, MEMORY_READ_PROBE, FOLLOW_UP_PROPOSE_PROBE, SUBAGENT_MEMORY_PROBE, SUBAGENT_PROVIDER_PROBE, CONNECTOR_TOOL_PROBE, NO_TOOL_CALL_PROBE, CONNECTOR_RESULT_SAMPLE, CONNECTOR_ARGUMENT_SAMPLE, LONG_ACTIVITY_PROBE, PROACTIVE_DEFAULT_FINDING, proactiveOutput, ASK_CARD_PROBE, FAKE_STARTER_PROMPTS } from "./fake-hermes/scenario-fixtures.ts";
export { DEMO_CONNECTOR, DEMO_TOKEN_OK, DEMO_TOKEN_BAD, AGENDA_CONNECTOR } from "./fake-hermes/connectors.ts";
export { FAKE_DASHBOARD_TOKEN, FAKE_USAGE } from "./fake-hermes/runtime-fixtures.ts";
export { type DemoScript, type ConnectorToolCall, type ProactiveScript, type FakeHermes } from "./fake-hermes/state.ts";
