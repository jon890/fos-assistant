import { type FakeHermesState, type DemoScript } from "./state.ts";
import { createLifecycle, send, readBody } from "./lifecycle.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import {
  TEST_BLOCK_PROVIDER_PATH,
  TEST_CLEAR_BLOCKED_PATH,
  TEST_BUSY_PATH,
  TEST_CLEAR_BUSY_PATH,
  TEST_READINESS_OUTAGE_PATH,
  TEST_HOLD_NEXT_CONFIG_PATH,
  TEST_RELEASE_HELD_CONFIG_PATH,
  TEST_SANDBOX_UNAVAILABLE_PATH,
  TEST_LAST_SANDBOX_OWNER_PATH,
  TEST_HOLD_NEXT_RUN_PATH,
  TEST_WAIT_HELD_RUN_PATH,
  TEST_RELEASE_HELD_RUN_PATH,
  TEST_RELEASE_LONG_ACTIVITY_PATH,
  TEST_SCRIPT_PATH,
  TEST_PROACTIVE_OUTPUT_PATH,
  TEST_LAST_SUBMITTED_RUNTIME_PATH,
  TEST_HOLD_NEXT_SOUL_PATH,
  TEST_RELEASE_HELD_SOUL_PATH,
} from "./runtime-fixtures.ts";
/** 다른 프로세스에서 시험 상태를 바꾸는 제어 경로다. */
export function createControlRoutes(state: FakeHermesState, { releaseHeldRun, releaseHeldSoul, releaseLongActivity }: ReturnType<typeof createLifecycle>) {
  return async (request: IncomingMessage, response: ServerResponse, path: string): Promise<void> => {
      // 검사가 실패해 finally까지 가지 못해도 다음 검사에 장애나 보류 설정을 넘기지 않는다.
      // profile, 실행과 session 기록은 지우지 않는다. 그것들은 DB와 함께 각 검사가 소유한다.
      if (request.method === "POST" && path === "/__test/reset-controls") {
        state.busy = false;
        state.readinessOutage = undefined;
        state.blockedProviders.clear();
        state.holdNextRun = false;
        const releasedRunId = releaseHeldRun();
        const releasedRun = releasedRunId === undefined ? undefined : state.runs.get(releasedRunId);
        if (releasedRun !== undefined) releasedRun.status = "completed";
        releaseHeldSoul();
        releaseLongActivity();
        state.holdNextConfig = false;
        state.heldConfigWaiter?.();
        state.releaseConfig?.();
        state.heldConfigReady = undefined;
        state.heldConfigWaiter = undefined;
        state.releaseConfig = undefined;
        state.sandboxUnavailable = false;
        state.droppedToolset = undefined;
        state.proactiveScript = undefined;
        state.openProactiveGate?.();
        state.proactiveGate = undefined;
        state.openProactiveGate = undefined;
        state.lastSubmittedRuntime = {};
        return send(response, 204, null);
      }

      const blockMatch = TEST_BLOCK_PROVIDER_PATH.exec(path);
      if (request.method === "POST" && blockMatch) {
        state.blockedProviders.add(blockMatch[1]!);
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BLOCKED_PATH) {
        state.blockedProviders.clear();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_BUSY_PATH) {
        state.busy = true;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BUSY_PATH) {
        state.busy = false;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_READINESS_OUTAGE_PATH) {
        const { outage } = JSON.parse((await readBody(request)) || "{}") as { outage?: unknown };
        if (outage !== undefined && outage !== "busy" && outage !== "unavailable" && outage !== "timeout") {
          return send(response, 400, { error: "outage must be busy, unavailable, timeout, or omitted" });
        }
        state.readinessOutage = outage;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_CONFIG_PATH) {
        state.holdNextConfig = true;
        state.heldConfigReady = new Promise<void>((done) => { state.heldConfigWaiter = done; });
        return send(response, 204, null);
      }
      if (request.method === "POST" && path === TEST_RELEASE_HELD_CONFIG_PATH) {
        state.releaseConfig?.();
        return send(response, 204, null);
      }
      if (request.method === "POST" && path === TEST_SANDBOX_UNAVAILABLE_PATH) {
        const { unavailable } = JSON.parse((await readBody(request)) || "{}") as { unavailable?: unknown };
        if (typeof unavailable !== "boolean") return send(response, 400, { error: "unavailable must be a boolean" });
        state.sandboxUnavailable = unavailable;
        return send(response, 204, null);
      }
      if (request.method === "GET" && path === TEST_LAST_SANDBOX_OWNER_PATH) {
        return send(response, 200, { sandboxOwner: state.lastSandboxOwner });
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_RUN_PATH) {
        state.holdNextRun = true;
        state.heldRunReady = new Promise<void>((done) => {
          state.heldRunWaiter = done;
        });
        return send(response, 204, null);
      }

      if (request.method === "GET" && path === TEST_WAIT_HELD_RUN_PATH) {
        if (state.heldRunReady === undefined) return send(response, 409, { error: "no held run is pending" });
        await state.heldRunReady;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_HELD_RUN_PATH) {
        state.holdNextRun = false;
        const releasedRunId = releaseHeldRun();
        if (releasedRunId === undefined) return send(response, 204, null);
        const run = state.runs.get(releasedRunId);
        if (run !== undefined) run.status = "completed";
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_LONG_ACTIVITY_PATH) {
        releaseLongActivity();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_SCRIPT_PATH) {
        const script = JSON.parse(await readBody(request)) as DemoScript;
        state.scripts.set(script.input, script);
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_PROACTIVE_OUTPUT_PATH) {
        const { output } = JSON.parse(await readBody(request)) as { output: string };
        state.proactiveScript = { output, tools: [] };
        return send(response, 204, null);
      }

      if (request.method === "GET" && path === TEST_LAST_SUBMITTED_RUNTIME_PATH) {
        return send(response, 200, state.lastSubmittedRuntime);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_SOUL_PATH) {
        state.holdNextSoul = true;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_HELD_SOUL_PATH) {
        // 대기 표시는 붙잡은 응답이 없어도 끈다. 클릭 전에 실패한 검사가 남긴 홀드 때문에 다음 검사가
        // 걸리지 않게 하기 위해서다.
        releaseHeldSoul();
        return send(response, 204, null);
      }

  };
}
