import { type FakeHermesState } from "./state.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import { FAKE_DASHBOARD_TOKEN } from "./runtime-fixtures.ts";
import { randomUUID } from "node:crypto";
/** 인증과 실행·응답의 보류 해제를 맡는다. */
export function createLifecycle(state: FakeHermesState) {
  /** 실제 Hermes 가 모르는 run 에 주는 404 본문이다. 지운 run 과 한 번도 없던 run 이 같다. */
  const runNotFound = (runId: string) => ({
    error: { message: `Run not found: ${runId}`, type: "invalid_request_error", code: "run_not_found" },
  });
  /** 지연 중인 실행 하나를 끝낸다. 이미 중지된 실행의 상태는 덮어쓰지 않는다. */
  const finishSlowRun = (runId: string) => {
    if (!state.slowActive.delete(runId)) return;
    const run = state.runs.get(runId);
    if (run !== undefined && run.status === "running") run.status = "completed";
  };

  const authorized = (request: IncomingMessage, profile: string): boolean => {
    const expected = state.keys[profile];
    return expected !== undefined && request.headers.authorization === `Bearer ${expected}`;
  };

  /** 대시보드 경로는 profile 별 key 가 아니라 기계용 토큰 하나로 열린다. */
  const dashboardAuthorized = (request: IncomingMessage): boolean =>
    request.headers.authorization === `Bearer ${FAKE_DASHBOARD_TOKEN}`;

  /** 붙잡은 성격 읽기 응답을 보내고 대기 표시를 끈다. 붙잡은 것이 없어도 대기 표시는 끈다. */
  const releaseHeldSoul = () => {
    state.holdNextSoul = false;
    const held = state.heldSoul;
    state.heldSoul = undefined;
    if (held !== undefined) send(held.response, 200, held.payload);
  };
  /** 붙잡은 실행을 풀되 실행 상태는 바꾸지 않는다. */
  const releaseHeldRun = (): string | undefined => {
    const runId = state.heldRunId;
    state.heldRunWaiter?.();
    state.heldRunId = undefined;
    state.heldRunReady = undefined;
    state.heldRunWaiter = undefined;
    return runId;
  };

  /** 기다리는 긴 작업 과정 스트림을 풀고 대기 표시를 지운다. 기다리는 것이 없으면 아무것도 하지 않는다. */
  const releaseLongActivity = () => {
    const gate = state.longActivityGate;
    state.longActivityGate = undefined;
    gate?.();
  };
  return { runNotFound, finishSlowRun, authorized, dashboardAuthorized, releaseHeldSoul, releaseHeldRun, releaseLongActivity };
}

export function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function shortId(): string {
  return randomUUID().replaceAll("-", "").slice(0, 12);
}

/**
 * 본문을 읽는다.
 *
 * <p>Spring 의 `RestClient` 는 요청 본문을 chunked 로 흘려보낸다. Node 의 요청 스트림은 두 인코딩을
 * 모두 같은 방식으로 내주므로, 여기서는 조각을 모으기만 하면 된다.
 */
export async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf-8");
}

export function send(response: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
  });
  response.end(body);
}

/**
 * 실제 Hermes 가 MCP 도구의 `tool.started` 에 싣는 `preview` 다. 인자 전체가 아니라 `query`, `text`, `command`, `path`, `name`,
 * `prompt`, `code`, `goal` 중 처음 있는 인자 하나의 값이고, 그 인자가 없으면 null 이다(`agent/display.py` 의 `_primary_arg_preview`).
 */
export function toolStartedPreview(argsJson: string): string | null {
  const args = JSON.parse(argsJson) as Record<string, unknown>;
  const key = ["query", "text", "command", "path", "name", "prompt", "code", "goal"].find((candidate) => candidate in args);
  return key === undefined ? null : String(args[key]);
}

export function event(response: ServerResponse, payload: unknown): void {
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}
