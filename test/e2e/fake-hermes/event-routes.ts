import { type FakeHermesState } from "./state.ts";
import { createLifecycle, send, wait, event, toolStartedPreview } from "./lifecycle.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import { RUN_EVENTS_PATH } from "./runtime-fixtures.ts";
import {
  specialOutputFor,
  LONG_ACTIVITY_PROBE,
  CONNECTOR_RESULT_SAMPLE,
  NO_TOOL_CALL_PROBE,
  TOOL_DETAIL_SAMPLE,
  SKILL_VIEW_CALL,
  SUBAGENT_PROVIDER_PROBE,
} from "./scenario-fixtures.ts";
/** 실행의 사건 스트림을 실제 Hermes 응답 모양으로 흘린다. */
export function createEventRoutes(state: FakeHermesState, { authorized, runNotFound }: ReturnType<typeof createLifecycle>) {
  return async (request: IncomingMessage, response: ServerResponse, path: string): Promise<void> => {
        const eventMatch = RUN_EVENTS_PATH.exec(path);
        if (eventMatch) {
          const [, profile, runId] = eventMatch;
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          state.eventsOpened.add(runId!);
          for (const done of state.eventsWaiters.get(runId!) ?? []) done();
          state.eventsWaiters.delete(runId!);
          const run = state.runs.get(runId);
          if (!run) return send(response, 404, runNotFound(runId!));
          if (run.input === "사건 스트림 실패 검사") {
            return send(response, 503, { error: "event stream unavailable" });
          }
          response.writeHead(200, {
            "Content-Type": "text/event-stream; charset=utf-8",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          });
          response.write(": keepalive\n\n");
          // 지연 중인 실행은 끝나거나 중지될 때까지 스트림을 연 채로 둔다. 실제 Hermes 가 도는 동안 그렇게 한다.
          while (state.slowActive.has(runId!) && !response.destroyed) await wait(20);
          // 살펴보기 실행이다. 도구 사건과 마지막 답 글을 흘린다. Control Plane 은 답 조각을 화면으로 보내지 않아야 한다.
          if (run.proactive !== undefined) {
            if (run.proactive.gate !== undefined) {
              await Promise.race([run.proactive.gate, new Promise<void>((resolve) => response.on("close", resolve))]);
            }
            for (const tool of run.proactive.tools) {
              event(response, { event: "tool.started", tool, preview: tool });
              event(response, { event: "tool.completed", tool, duration: 0.1, error: false });
            }
            event(response, { event: "message.delta", delta: run.output });
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          if (run.input === "중지 조각 전 검사" && run.status !== "completed") {
            if (run.status === "cancelled") {
              response.end();
            } else {
              state.emptyUntilStopped.set(runId!, response);
              response.on("close", () => state.emptyUntilStopped.delete(runId!));
            }
            return;
          }
          // 바깥 도구를 쓰는 run 이다. 도구 사건을 흘린 뒤에 끝나므로, 답을 받은 때에는 그 사건이 저장돼 있다.
          if (run.outsideTool === true) {
            event(response, { event: "tool.started", tool: "web_search", preview: "web_search" });
            event(response, { event: "tool.completed", tool: "web_search", duration: 0.1, error: false });
            event(response, { event: "message.delta", delta: run.output });
            run.status = "completed";
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          const script = state.scripts.get(run.input);
          if (script !== undefined) {
            for (const scripted of script.events ?? []) {
              if (scripted.event === "subagent.start" && typeof scripted.child_session_id === "string") {
                state.childUsages.set(scripted.child_session_id, { profile: profile!, parent: run.session_id, reads: 0, delayed: false });
              }
              event(response, scripted);
            }
            if (script.pause === true) {
              await new Promise<void>((resolve) => {
                state.longActivityGate = resolve;
                response.on("close", resolve);
              });
            }
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          // 실제 Hermes v0.21.0 이 보내는 형태다.
          // 사건 이름은 `event`, 조각은 `delta`, 도구 이름은 `tool`, 설명은 `preview` 다.
          // 여기가 실제와 어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.
          const streamedOutput = specialOutputFor(run.input);
          event(response, {
            event: "message.delta",
            delta: run.input === LONG_ACTIVITY_PROBE ? "긴 작업 과정"
              : streamedOutput === null ? "화면에서만 " : streamedOutput.slice(0, 80),
          });
          // 도구 줄이 많은 작업 과정이다. 시작만 한 도구 줄을 남기고 두 번 기다려, 검사가 도는 중인 블록의
          // 높이와 스크롤을 본다. 이 입력에는 다른 도구 사건과 하위 에이전트 사건을 보내지 않는다.
          if (run.input === LONG_ACTIVITY_PROBE) {
            for (let step = 1; step <= 30; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "tool.started", tool: "terminal", preview: "단계 31" });
            await new Promise<void>((resolve) => {
              state.longActivityGate = resolve;
              response.on("close", resolve);
            });
            event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            for (let step = 32; step <= 41; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "tool.started", tool: "terminal", preview: "단계 42" });
            await new Promise<void>((resolve) => {
              state.longActivityGate = resolve;
              response.on("close", resolve);
            });
            event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            for (let step = 43; step <= 52; step += 1) {
              event(response, { event: "tool.started", tool: "terminal", preview: `단계 ${step}` });
              event(response, { event: "tool.completed", tool: "terminal", duration: 0.1, error: false });
            }
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          // 중지 뒤에도 Hermes 사건 스트림이 닫히지 않는 경우를 재현한다. Control Plane 이 유예 시간 뒤
          // 이 연결을 직접 닫아야 한다.
          if (run.input === "중지 스트림 유지 검사") return;
          // 취소 뒤 Hermes 가 최종 output 을 비워도, 이미 화면으로 보낸 첫 조각은 남겨야 한다.
          if (run.input === "중지 빈 답 검사") {
            response.end();
            return;
          }
          if (run.interruptEvents) {
            response.end();
            return;
          }
          if (streamedOutput === null) {
            event(response, { event: "message.delta", delta: `보이는 조각: ${run.input}` });
          } else {
            for (let offset = 80; offset < streamedOutput.length; offset += 80) {
              if (run.input === "긴 답 스트림 검사") await wait(25);
              event(response, { event: "message.delta", delta: streamedOutput.slice(offset, offset + 80) });
            }
          }
          // 정책이 허용한 커넥터 도구 호출이다. 실제 Hermes(v0.21.5)가 붙은 서버의 도구에 보내는 모양이다. 모든 사건에 `run_id` 와
          // `timestamp` 가 있다. 시작 사건의 `preview` 는 인자 하나의 값이고, 완료 사건의 `preview` 는 결과다.
          // 정책 hook 이 막은 호출은 시작도 완료도 보내지 않으므로 허용된 호출만 여기에 온다.
          for (const connectorCall of run.connectorCalls ?? []) {
            const common = { run_id: runId, timestamp: Date.now() / 1000, tool: connectorCall.hermesTool };
            event(response, { event: "tool.started", ...common, preview: toolStartedPreview(connectorCall.argsJson) });
            event(response, { event: "tool.completed", ...common, duration: 0.05, error: false,
              preview: JSON.stringify({ result: CONNECTOR_RESULT_SAMPLE }) });
          }
          // 도구를 부르지 않고 답만 하는 실행이다. 실행 기록에 도구 사건이 하나도 없는 실행을 만든다.
          if (run.input === NO_TOOL_CALL_PROBE) {
            event(response, { event: "run.completed" });
            response.end();
            return;
          }
          const redactDetail = run.input === "도구 가리기 검사" || run.input === "스트림 정본 검사";
          event(response, { event: "tool.started", tool: "fake-tool",
            preview: redactDetail ? TOOL_DETAIL_SAMPLE : "started" });
          event(response, { event: "tool.completed", tool: "fake-tool", duration: 0.1,
            result: redactDetail ? JSON.parse(TOOL_DETAIL_SAMPLE) : undefined,
            error: run.input === "병렬 하위 에이전트 검사" });
          event(response, { event: "tool.started", tool: "fake-reader", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-reader", duration: 0.25, error: false });
          // 모델이 스킬을 읽은 사건이다. 실제 Hermes 는 `skill_view` 의 `preview` 에 스킬 이름을 싣는다.
          if (run.input === "스킬 읽기 검사") {
            event(response, { event: "tool.started", tool: "skill_view", preview: "shopping" });
            event(response, { event: "tool.completed", tool: "skill_view", duration: 0.05, error: false });
          }
          // 스킬 커맨드로 바꾼 입력이다. 실제 Hermes 의 모델도 이 입력을 받으면 그 이름으로 `skill_view` 를 부른다.
          const commandedSkill = SKILL_VIEW_CALL.exec(run.input)?.[1];
          if (commandedSkill !== undefined) {
            event(response, { event: "tool.started", tool: "skill_view", preview: commandedSkill });
            event(response, { event: "tool.completed", tool: "skill_view", duration: 0.05, error: false });
          }
          // 하위 에이전트 사건은 도구 사건과 어미가 다르다. `.started` 와 `.completed` 가 아니다.
          // Hermes v0.21.0 은 여기에 session 번호를 싣지 않고 `preview` 만 보낸다.
          if (run.input === "자식 늦은 완료 검사" || run.input === "자식 완료 사건 없음 검사" || run.input === "압축 뒤 자식 완료 검사"
            || run.input === SUBAGENT_PROVIDER_PROBE) {
            const childSessionId = `child-${run.run_id}`;
            const parentSessionId = run.input === "압축 뒤 자식 완료 검사"
              ? `compacted-${run.session_id}`
              : run.session_id;
            state.childUsages.set(childSessionId, { profile: profile!, parent: parentSessionId,
              reads: 0, delayed: run.input === "자식 늦은 완료 검사",
              observation: { childSessionId, parentSessionId, registeredAt: new Date().toISOString(),
                parentStreamClosedAt: null, releasedAt: null, reads: 0, requests: [] },
              ...(run.input === SUBAGENT_PROVIDER_PROBE ? { model: "example-model-large", provider: "anthropic" } : {}) });
            const child = { subagent_id: `sa-${run.run_id}`, goal: "부모 뒤에 끝나는 조사",
              model: "example-fast", child_session_id: childSessionId };
            event(response, { event: "subagent.start", ...child });
            event(response, { event: "run.completed" });
            // 부모 스트림을 먼저 닫는다. 늦은 자식 완료는 첫 session 조회 뒤에만 보이며,
            // 이미 닫힌 부모 스트림에는 완료 사건을 전달할 수 없다.
            response.end();
            state.childUsages.get(childSessionId)!.observation!.parentStreamClosedAt = new Date().toISOString();
            return;
          }
          if (run.input === "병렬 하위 에이전트 검사") {
            const first = { goal: "첫째 조사", child_session_id: "child-first" };
            const second = { goal: "둘째 조사", child_session_id: "child-second" };
            event(response, { event: "subagent.start", ...first });
            event(response, { event: "subagent.start", ...second });
            event(response, { event: "subagent.complete", ...second, model: "model-second",
              status: "completed", duration_seconds: 2, input_tokens: 200, output_tokens: 20 });
            event(response, { event: "subagent.complete", ...first, model: "model-first",
              status: "completed", duration_seconds: 1, input_tokens: 100, output_tokens: 10 });
          } else if (run.input === "하위 에이전트 칸 검사") {
            const subagent = { subagent_id: "sa-1", goal: "숙소 후보를 조사한다", model: "z-ai/glm-5.2", child_session_id: "child-1" };
            event(response, { event: "subagent.start", preview: "하위 에이전트가 찾기 시작했다", ...subagent });
            event(response, { event: "subagent.complete", preview: "하위 에이전트가 찾기를 마쳤다", ...subagent,
              status: "completed", duration_seconds: 1.5, input_tokens: 12300, output_tokens: 410 });
          } else {
            event(response, { event: "subagent.start", preview: "하위 에이전트가 찾기 시작했다" });
            event(response, { event: "subagent.complete", preview: "하위 에이전트가 찾기를 마쳤다" });
          }
          event(response, { event: "run.completed" });
          response.end();
          return;
        }
  };
}
