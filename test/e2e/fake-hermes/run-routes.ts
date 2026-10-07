import { type FakeHermesState, type ConnectorCall } from "./state.ts";
import { createLifecycle, send, readBody, shortId, wait } from "./lifecycle.ts";
import { createMcp } from "./mcp.ts";
import { type IncomingMessage, type ServerResponse } from "node:http";
import {
  RUN_STOP_PATH,
  RUN_PATH,
  RATE_LIMITED,
  FAKE_USAGE,
  PROVIDER_AUTH_FAILED,
  DEFAULT_RUNTIME,
  RUN_STATUS_PATH,
  ENABLED_TOOLSETS_PATH,
  DEFAULT_API_SERVER_TOOLSETS,
  TOOLSET_CATALOG,
  MODEL_OPTIONS_PATH,
  SESSION_PATH,
  CAPABILITIES_PATH,
} from "./runtime-fixtures.ts";
import {
  splitArtifactPreamble,
  STARTER_MARK,
  ARTIFACT_PROBE,
  writeArtifactDraft,
  ARTIFACT_SAME_NAME_PROBE,
  writeSameNameArtifacts,
  ARTIFACT_WRITE_PROBE,
  SUBAGENT_MEMORY_PROBE,
  MEMORY_READ_PROBE,
  FOLLOW_UP_PROPOSE_PROBE,
  CONNECTOR_TOOL_PROBE,
  withoutAskGuide,
  withoutResponseGuide,
  PROACTIVE_CHECK_CALL,
  defaultProactiveOutput,
  specialOutputFor,
  DEFAULT_PROACTIVE_TOOLS,
  actualModelFor,
  SESSION_MODEL_PROBE,
} from "./scenario-fixtures.ts";
/** 실행 제출, 중지와 상태 조회를 처리한다. */
export function createRunRoutes(state: FakeHermesState, { authorized, runNotFound, releaseHeldRun, finishSlowRun }: ReturnType<typeof createLifecycle>, { writeArtifactViaMcp, registerSubagent, readMemoryViaMcp, callControlPlaneToolViaMcp, judgeConnectorCalls }: ReturnType<typeof createMcp>) {
  return async (request: IncomingMessage, response: ServerResponse, path: string): Promise<void> => {
      if (request.method === "POST") {
        const stopMatch = RUN_STOP_PATH.exec(path);
        if (stopMatch) {
          const [, profile, runId] = stopMatch;
          if (!authorized(request, profile!)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const run = state.runs.get(runId!);
          if (!run) return send(response, 404, runNotFound(runId!));
          run.status = "cancelled";
          if (run.input === "중지 빈 답 검사" || run.input === "중지 조각 전 검사") run.output = "";
          state.emptyUntilStopped.get(runId!)?.end();
          state.emptyUntilStopped.delete(runId!);
          state.stoppedRuns.push(runId!);
          if (state.heldRunId === runId) releaseHeldRun();
          state.slowActive.delete(runId!);
          return send(response, 200, { status: "stopping" });
        }
        const match = RUN_PATH.exec(path);
        if (!match) return send(response, 404, { error: "not found" });
        const profile = match[1];
        if (!authorized(request, profile)) {
          return send(response, 401, { error: "bad key for this profile" });
        }
        state.submitCount += 1;
        // 한도에 닿은 gateway 는 본문을 읽기 전에 거절한다. 실행을 만들지 않는다.
        if (state.busy) return send(response, 429, RATE_LIMITED);

        const raw = await readBody(request);
        const submitted = (raw.length > 0 ? JSON.parse(raw) : {}) as {
          input?: string;
          instructions?: string;
          session_id?: string;
          provider?: string;
          model?: string;
          model_options?: { reasoning?: { effort?: string } };
        };
        const { folder: artifactFolder, conversationId: artifactConversationId, rest: input } = splitArtifactPreamble(submitted.input ?? "");
        // 추천을 만드는 실행은 Control Plane 이 새 대화 화면을 열 때마다 끼어든다. 대화 실행을 관찰하려는 검사가
        // 그 실행에 흔들리지 않도록 「마지막 제출」 기록을 덮어쓰지 않고 `holdNextRun` 도 가져가지 않는다.
        const starterRun = input.startsWith(STARTER_MARK);
        if (!starterRun) {
          state.lastSubmittedInstructions = submitted.instructions;
          // 되돌려 받는 쪽은 원문을 본다. 결과물 폴더 단락이 붙었는지 검사가 이것으로 안다.
          state.lastSubmittedInput = submitted.input;
        }
        if (input === ARTIFACT_PROBE && artifactFolder !== undefined) writeArtifactDraft(artifactFolder);
        if (input === ARTIFACT_SAME_NAME_PROBE && artifactFolder !== undefined) writeSameNameArtifacts(artifactFolder);
        if (input === ARTIFACT_WRITE_PROBE && artifactConversationId !== undefined) await writeArtifactViaMcp(artifactConversationId, submitted.session_id);
        const registeredChild = input.startsWith(SUBAGENT_MEMORY_PROBE)
          ? await registerSubagent(submitted.session_id)
          : undefined;
        const memoryReadPrefix = `${MEMORY_READ_PROBE} `;
        const memoryReadOutput = input.startsWith(memoryReadPrefix)
          ? await readMemoryViaMcp(Number(input.slice(memoryReadPrefix.length)), submitted.session_id)
          : undefined;
        const followUpProposePrefix = `${FOLLOW_UP_PROPOSE_PROBE} `;
        const followUpProposeOutput = input.startsWith(followUpProposePrefix)
          ? await callControlPlaneToolViaMcp(
            "follow_up_propose",
            JSON.parse(input.slice(followUpProposePrefix.length)) as Record<string, unknown>,
            submitted.session_id,
          )
          : undefined;
        const rememberCall = state.memoryRememberCalls.get(input);
        const rememberOutput = rememberCall !== undefined
          ? await callControlPlaneToolViaMcp("memory_remember", rememberCall, submitted.session_id)
          : undefined;
        const connectorCalls: ConnectorCall[] = [];
        const connectorOutput = input.startsWith(CONNECTOR_TOOL_PROBE)
          ? await judgeConnectorCalls(
            profile!,
            state.profiles.get(profile!)?.MCP_FOS_ASSISTANT_API_KEY,
            submitted.session_id,
            input.split("\n").slice(1).map((line) => line.trim()).filter((line) => line.length > 0),
            `connector-call-${state.submitCount}`,
            connectorCalls,
          )
          : undefined;
        if (!starterRun) {
          state.lastSubmittedRuntime = {
            provider: submitted.provider,
            model: submitted.model,
            reasoningEffort: submitted.model_options?.reasoning?.effort,
          };
        }
        const runId = `run_${shortId()}`;
        const sessionId = submitted.session_id ?? `sess_${shortId()}`;

        // 실제 Hermes 는 provider 만 받으면 config 의 모델 문자열을 그대로 써서 실패한다.
        if (submitted.provider !== undefined && submitted.model === undefined) {
          state.runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider ?? null,
            error: "No LLM provider configured. Run `hermes model` to select a provider.",
            output: "",
            input,
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }

        // 그 provider 의 계정이 전부 막힌 상태다. 접수는 되고 나중에 failed 로 바뀐다.
        if (submitted.provider !== undefined && state.blockedProviders.has(submitted.provider)) {
          state.runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider,
            error: PROVIDER_AUTH_FAILED,
            output: "",
            input,
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }
        const echoed = withoutAskGuide(withoutResponseGuide(submitted.instructions ?? ""));
        const instructionsEcho = echoed.length > 0 ? ` [instructions: ${echoed}]` : "";
        // 살펴보기 실행은 넣어 둔 각본 하나를 가져간다. 각본이 없으면 기본 답을 준다.
        const proactiveRun = !starterRun && input.includes(PROACTIVE_CHECK_CALL);
        const script = proactiveRun ? state.proactiveScript : undefined;
        if (proactiveRun) {
          state.proactiveScript = undefined;
          state.proactiveInputs.push({ profile: profile!, input: submitted.input ?? "" });
        }
        const heldByNext = state.holdNextRun && !starterRun;
        if (heldByNext) state.holdNextRun = false;
        const held = heldByNext || script?.hold === true;
        const slow = !held && !starterRun && state.slowRunMs !== undefined;
        state.runs.set(runId, {
          run_id: runId,
          status: held || slow || state.outsideToolInputs.has(input) ? "running" : "completed",
          session_id: sessionId,
                  // 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다.
          model: submitted.model ?? profile!,
          output: (proactiveRun ? script?.output ?? defaultProactiveOutput() : undefined)
            ?? memoryReadOutput
            ?? followUpProposeOutput
            ?? rememberOutput
            ?? connectorOutput
            ?? (registeredChild === undefined ? undefined : `하위 에이전트 session: ${registeredChild}`)
            ?? state.scripts.get(input)?.output
            ?? specialOutputFor(input)
            ?? `[${state.who} on profile ${profile}]${instructionsEcho} ${input}`,
          input,
          provider: submitted.provider ?? null,
          interruptEvents: input === "스트림 중단 검사",
          usage: FAKE_USAGE,
          connectorCalls,
          outsideTool: state.outsideToolInputs.has(input),
          proactive: proactiveRun
            ? {
                tools: script === undefined ? DEFAULT_PROACTIVE_TOOLS : script.tools ?? [],
                gate: script?.waitBeforeEvents === true ? state.proactiveGate : undefined,
              }
            : undefined,
        });
        // 실제로 돈 모델은 세션 행과 v0.21.5 실행 조회의 runtime 에 남는다.
        // provider 와 모델을 빼고 온 실행은 profile 의 기본값으로 돈다.
        const served = {
          model: actualModelFor(input, submitted.model ?? DEFAULT_RUNTIME.model),
          provider:
            input === SESSION_MODEL_PROBE ? "nvidia" : submitted.provider ?? DEFAULT_RUNTIME.provider,
        };
        state.sessions.set(sessionId, served);
        const stored = state.runs.get(runId);
        if (stored) stored.runtime = { provider: served.provider, model: served.model, route_source: "global" };
        if (held) {
          state.heldRunId = runId;
          state.heldRunWaiter?.();
        }
        if (slow) {
          state.slowActive.set(runId, profile!);
          state.maxTotalConcurrency = Math.max(state.maxTotalConcurrency, state.slowActive.size);
          const sameProfile = [...state.slowActive.values()].filter((name) => name === profile).length;
          state.maxProfileConcurrency.set(profile!, Math.max(state.maxProfileConcurrency.get(profile!) ?? 0, sameProfile));
          setTimeout(() => finishSlowRun(runId), state.slowRunMs);
        }
        return send(response, 200, { run_id: runId, status: "queued" });
      }

      const match = RUN_STATUS_PATH.exec(path);
      if (!match) return send(response, 404, { error: "not found" });
      const [, profile, runId] = match;
      if (!authorized(request, profile)) {
        return send(response, 401, { error: "bad key for this profile" });
      }
      const run = state.runs.get(runId);
      if (!run) return send(response, 404, runNotFound(runId!));
      return send(response, 200, run);

  };
}
/** profile 의 도구 목록, 모델 선택지와 session 조회를 처리한다. */
export function createRuntimeRoutes(state: FakeHermesState, { authorized }: ReturnType<typeof createLifecycle>) {
  return async (request: IncomingMessage, response: ServerResponse, path: string): Promise<void> => {

        const enabledToolsetsMatch = ENABLED_TOOLSETS_PATH.exec(path);
        if (enabledToolsetsMatch) {
          const profile = enabledToolsetsMatch[1]!;
          if (!authorized(request, profile)) return send(response, 401, { error: "bad key for this profile" });
          if (state.readinessOutage === "busy") return send(response, 429, RATE_LIMITED);
          if (state.readinessOutage === "unavailable") return send(response, 503, { error: "Hermes is unavailable" });
          // Control Plane 의 Hermes read timeout(10초)보다 길게 기다려 실제 timeout 경로를 탄다.
          if (state.readinessOutage === "timeout") await wait(11_000);
          const enabled = new Set(state.apiServerToolsets.get(profile) ?? DEFAULT_API_SERVER_TOOLSETS);
          // 실제 listener 는 목록을 `data` 로 감싼다(v0.21.3 `gateway/platforms/api_server.py` 의 `_handle_toolsets`).
          return send(response, 200, {
            object: "list",
            platform: "api_server",
            data: TOOLSET_CATALOG.map((toolset) => ({ ...toolset, enabled: enabled.has(toolset.name) })),
          });
        }
        const modelMatch = MODEL_OPTIONS_PATH.exec(path);
        if (modelMatch) {
          const profile = modelMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          state.modelOptionsCalls += 1;
          // 실제 응답의 모양이다. 설정하지 않은 provider 도 빈 행으로 함께 온다.
          return send(response, 200, {
            ...DEFAULT_RUNTIME,
            providers: [
              {
                slug: DEFAULT_RUNTIME.provider,
                name: "OpenAI Codex",
                authenticated: true,
                models: [DEFAULT_RUNTIME.model, "example-model-mini", "example-fast", "example-balanced", "example-deep"],
                // 실제로 can_disable_reasoning 은 aggregator provider 의 모델에만 온다.
                // 이 대역은 reasoning 끄기(none)를 시험하려고 기본 모델에도 준다.
                // example-balanced 는 칸이 없는 모델(UNKNOWN)이다.
                capabilities: {
                  [DEFAULT_RUNTIME.model]: { reasoning: true, can_disable_reasoning: true },
                  "example-model-mini": { reasoning: false },
                  "example-fast": { reasoning: true },
                  "example-balanced": {},
                  "example-deep": { reasoning: true, can_disable_reasoning: false },
                },
              },
              { slug: "unconfigured", name: "Unconfigured", authenticated: false, models: [] },
            ],
          });
        }

        const sessionMatch = SESSION_PATH.exec(path);
        if (sessionMatch) {
          const [, profile, sessionId] = sessionMatch;
          if (!authorized(request, profile!)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const child = state.childUsages.get(sessionId!);
          if (child && child.profile === profile) {
            child.reads += 1;
            const ended = !child.delayed || child.reads > 1;
            return send(response, 200, { object: "session", session: {
              id: sessionId, source: "subagent", parent_session_id: child.parent,
              model: child.model ?? "example-fast", started_at: 1000, ended_at: ended ? 1002.5 : null,
              end_reason: ended ? "agent_close" : null,
              input_tokens: 100, cache_read_tokens: 50, cache_write_tokens: 10, output_tokens: 20,
            } });
          }
          const session = state.sessions.get(sessionId!);
          if (!session) return send(response, 404, { error: "no such session" });
          // 실제 v0.21.5 는 세션 행을 session 안에 감싸고 provider 칸을 주지 않는다(저장소의 billing_provider).
          return send(response, 200, { object: "session", session: { id: sessionId, model: session.model } });
        }

        const capabilitiesMatch = CAPABILITIES_PATH.exec(path);
        if (capabilitiesMatch) {
          const profile = capabilitiesMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          return send(response, 200, { model: "example-model", tools: [] });
        }

  };
}
