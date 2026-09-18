/**
 * 홈서버 없이 돌리기 위한 Hermes Runs API 대역이다.
 *
 * <p>Control Plane 이 실제로 부르는 것만 구현한다. profile 경로로 실행을 제출하고, 그 실행의 상태와
 * 토큰 수를 돌려준다. profile 마다 bearer key 도 검사한다. 라우팅을 잘못하면 조용히 성공하는 대신
 * 여기서 401 이 나게 하기 위해서다.
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";

const RUN_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs$/;
const RUN_STATUS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)$/;
const RUN_EVENTS_PATH = /^\/p\/([a-z0-9-]+)\/v1\/runs\/([A-Za-z0-9_-]+)\/events$/;
const MODEL_OPTIONS_PATH = /^\/p\/([a-z0-9-]+)\/api\/model\/options$/;
const SESSION_PATH = /^\/p\/([a-z0-9-]+)\/api\/sessions\/([A-Za-z0-9_-]+)$/;
/** Control Plane 이 주소를 저장하기 전에 닿는지 확인할 때 부른다. */
const CAPABILITIES_PATH = /^\/p\/([a-z0-9-]+)\/v1\/capabilities$/;
const TEST_BLOCK_PROVIDER_PATH = /^\/__test\/block-provider\/([a-z0-9-]+)$/;
const TEST_CLEAR_BLOCKED_PATH = "/__test/clear-blocked-providers";
const TEST_HOLD_NEXT_RUN_PATH = "/__test/hold-next-run";
const TEST_WAIT_HELD_RUN_PATH = "/__test/wait-held-run";
const TEST_RELEASE_HELD_RUN_PATH = "/__test/release-held-run";
const TEST_BUSY_PATH = "/__test/busy";
const TEST_CLEAR_BUSY_PATH = "/__test/clear-busy";

/**
 * 동시 실행 한도를 넘겼을 때 실제 Hermes 가 내는 본문이다.
 *
 * <p>공유 gateway 는 이 한도를 모든 profile 이 나눠 쓴다. 한 사람이 채우면 다른 사람이 이것을 받는다.
 */
const RATE_LIMITED = {
  error: {
    message: "Too many concurrent runs (max 16)",
    type: "rate_limit_error",
    code: "rate_limit_exceeded",
  },
} as const;

/**
 * 실행 하나가 보고하는 토큰 수다.
 *
 * <p>입력 120 중 80 이 캐시이고 출력이 40 이다. 사용량 시나리오가 이 값으로 환산 금액을 계산한다.
 */
export const FAKE_USAGE = {
  prompt_tokens: 120,
  completion_tokens: 40,
  total_tokens: 160,
  prompt_tokens_details: { cached_tokens: 80 },
} as const;

type Run = {
  run_id: string;
  status: string;
  session_id: string;
  /** 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다. */
  model: string;
  provider: string | null;
  error?: string;
  output: string;
  input: string;
  interruptEvents: boolean;
  usage: typeof FAKE_USAGE;
};

/**
 * 세션 하나가 마지막으로 실제로 쓴 provider 와 모델이다.
 *
 * <p>실제 Hermes 에서 이 값만 넘김이 일어난 뒤의 모델을 담는다. `GET /v1/runs/{id}` 는 담지 않는다.
 */
type Session = { model: string; provider: string | null };

/** 이 글을 입력으로 보내면 세션 조회가 실행에 실어 보낸 것과 다른 모델을 답한다. */
const SESSION_MODEL_PROBE = "세션 모델 검사";

/**
 * 그 실행이 실제로 쓴 모델을 정한다. 세션 행만 이 값을 갖는다.
 *
 * <p>가격을 검사하는 화면 검사들이 입력으로 모델을 고른다. 요청에 실어 보낸 모델과 다르게 답해야 실제로
 * 돈 모델을 읽고 있는지 알 수 있다.
 */
function actualModelFor(input: string, requested: string): string {
  if (input === SESSION_MODEL_PROBE) return "nvidia/nemotron-3.5-lightning-30b-a3b";
  if (input === "가격 없음 검사") return "unknown-model";
  if (input === "무료 모델 검사") return "gpt-zero";
  return requested;
}

/** 계정이 전부 막혔을 때 Hermes 가 붙이는 고정 접두사다. 실측한 문장이다. */
const PROVIDER_AUTH_FAILED =
  "\u26a0\ufe0f Provider authentication failed: No Codex credentials stored. Run `hermes auth` to authenticate.";

/**
 * Chief 에게만 주는 지시에 들어 있는 말이다.
 *
 * <p>Control Plane 의 `ResearchAndBuildFlow` 가 만드는 지시와 같아야 한다. 어긋나면 흐름 검사가
 * 계약을 지키지 않는 답을 받아 실패한다.
 */
const CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

/**
 * 흐름의 첫 단계가 돌려줄 답을 정한다.
 *
 * <p>Chief 의 지시에는 사용자가 보낸 글이 그대로 들어 있어, 그 글로 어떤 답을 줄지 고른다.
 */
function chiefOutputFor(input: string): string {
  if (input.includes("흐름 계약 위반 검사")) return "JSON 이 아니라 그냥 문장이다";
  if (input.includes("흐름 단독 검사")) return '{"research":"","build":""}';
  return '{"research":"전기차 보조금을 조사한다","build":"비교 표를 만든다"}';
}

function specialOutputFor(input: string): string | null {
  if (input.includes(CHIEF_MARK)) {
    return chiefOutputFor(input);
  }
  if (input === "마크다운 보안 검사") {
    return [
      "| 항목 | 값 |",
      "| --- | --- |",
      "| 표 | 정상 |",
      "",
      "<script>window.__unsafeAgentHtml = true</script>",
    ].join("\n");
  }
  if (input === "긴 답 스트림 검사") {
    return Array.from({ length: 80 }, (_, index) => `${index + 1}번째 긴 답 줄`).join("\n\n");
  }
  if (input === "코드 블록 검사") {
    return [
      "```java",
      "public class Greeting {",
      "  // 인사 횟수",
      "  private static final int COUNT = 3;",
      "  void greet() {",
      "    System.out.println(\"안녕하세요\");",
      "  }",
      "}",
      "```",
    ].join("\n");
  }
  return null;
}

function wait(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function shortId(): string {
  return randomUUID().replaceAll("-", "").slice(0, 12);
}

/**
 * 본문을 읽는다.
 *
 * <p>Spring 의 `RestClient` 는 요청 본문을 chunked 로 흘려보낸다. Node 의 요청 스트림은 두 인코딩을
 * 모두 같은 방식으로 내주므로, 여기서는 조각을 모으기만 하면 된다.
 */
async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString("utf-8");
}

function send(response: ServerResponse, status: number, payload: unknown): void {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
  });
  response.end(body);
}

function event(response: ServerResponse, payload: unknown): void {
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

export type FakeHermes = {
  readonly baseUrl: string;
  lastSubmittedInstructions(): string | undefined;
  /** 마지막 실행 요청이 실어 온 provider 와 모델 */
  lastSubmittedRuntime(): { provider?: string; model?: string };
  blockProvider(provider: string): void;
  clearBlockedProviders(): void;
  /** 실행 제출을 429 로 거절하게 한다. 공유 gateway 가 한도에 닿은 상태를 흉내 낸다. */
  busy(): void;
  clearBusy(): void;
  /** 실행 제출을 받은 횟수다. 429 뒤에 다시 보내지 않는 것을 이 수로 본다. */
  submitCount(): number;
  holdNextRun(): void;
  waitForHeldRun(): Promise<void>;
  releaseHeldRun(): void;
  close(): Promise<void>;
};

/**
 * fake Hermes 를 띄우고 그 주소를 돌려준다.
 *
 * @param profileKeys profile 이름과 그 profile 의 API server key
 * @param label 이 대역을 다른 대역과 구분하는 이름. 실행의 답에 그대로 실린다. 주소를 옮기는 검사가
 *     답이 어느 대역에서 왔는지 보는 데 쓴다
 */
export function startFakeHermes(
  profileKeys: Record<string, string>,
  label?: string,
): Promise<FakeHermes> {
  const who = label === undefined ? "fake hermes" : `fake hermes ${label}`;
  const runs = new Map<string, Run>();
  const sessions = new Map<string, Session>();
  const blockedProviders = new Set<string>();
  let busy = false;
  let submitCount = 0;
  let lastSubmittedRuntime: { provider?: string; model?: string } = {};
  let holdNextRun = false;
  let heldRunId: string | undefined;
  let heldRunWaiter: (() => void) | undefined;
  let heldRunReady: Promise<void> | undefined;
  let lastSubmittedInstructions: string | undefined;

  const authorized = (request: IncomingMessage, profile: string): boolean => {
    const expected = profileKeys[profile];
    return expected !== undefined && request.headers.authorization === `Bearer ${expected}`;
  };

  const server: Server = createServer((request, response) => {
    void (async () => {
      const path = request.url ?? "";

      const blockMatch = TEST_BLOCK_PROVIDER_PATH.exec(path);
      if (request.method === "POST" && blockMatch) {
        blockedProviders.add(blockMatch[1]!);
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BLOCKED_PATH) {
        blockedProviders.clear();
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_BUSY_PATH) {
        busy = true;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_CLEAR_BUSY_PATH) {
        busy = false;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_HOLD_NEXT_RUN_PATH) {
        holdNextRun = true;
        heldRunReady = new Promise<void>((done) => {
          heldRunWaiter = done;
        });
        return send(response, 204, null);
      }

      if (request.method === "GET" && path === TEST_WAIT_HELD_RUN_PATH) {
        if (heldRunReady === undefined) return send(response, 409, { error: "no held run is pending" });
        await heldRunReady;
        return send(response, 204, null);
      }

      if (request.method === "POST" && path === TEST_RELEASE_HELD_RUN_PATH) {
        holdNextRun = false;
        heldRunWaiter?.();
        if (heldRunId === undefined) {
          heldRunReady = undefined;
          heldRunWaiter = undefined;
          return send(response, 204, null);
        }
        const run = runs.get(heldRunId);
        if (run !== undefined) run.status = "completed";
        heldRunId = undefined;
        heldRunReady = undefined;
        heldRunWaiter = undefined;
        return send(response, 204, null);
      }

      if (request.method === "GET") {
        const modelMatch = MODEL_OPTIONS_PATH.exec(path);
        if (modelMatch) {
          const profile = modelMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          return send(response, 200, { model: "gpt-5.5", provider: "openai-codex", providers: [] });
        }

        const sessionMatch = SESSION_PATH.exec(path);
        if (sessionMatch) {
          const [, profile, sessionId] = sessionMatch;
          if (!authorized(request, profile!)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const session = sessions.get(sessionId!);
          if (!session) return send(response, 404, { error: "no such session" });
          return send(response, 200, { session_id: sessionId, ...session });
        }

        const capabilitiesMatch = CAPABILITIES_PATH.exec(path);
        if (capabilitiesMatch) {
          const profile = capabilitiesMatch[1];
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          return send(response, 200, { model: "gpt-5.5", tools: [] });
        }

        const eventMatch = RUN_EVENTS_PATH.exec(path);
        if (eventMatch) {
          const [, profile, runId] = eventMatch;
          if (!authorized(request, profile)) {
            return send(response, 401, { error: "bad key for this profile" });
          }
          const run = runs.get(runId);
          if (!run) return send(response, 404, { error: "no such run" });
          response.writeHead(200, {
            "Content-Type": "text/event-stream; charset=utf-8",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
          });
          response.write(": keepalive\n\n");
          // 실제 Hermes v0.21.0 이 보내는 형태다.
          // 사건 이름은 `event`, 조각은 `delta`, 도구 이름은 `tool`, 설명은 `preview` 다.
          // 여기가 실제와 어긋나면 테스트는 통과하는데 운영에서 조각이 흐르지 않는다.
          const streamedOutput = specialOutputFor(run.input);
          event(response, {
            event: "message.delta",
            delta: streamedOutput === null ? "화면에서만 " : streamedOutput.slice(0, 80),
          });
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
          event(response, { event: "tool.started", tool: "fake-tool", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-tool", duration: 0.1, error: false });
          event(response, { event: "tool.started", tool: "fake-reader", preview: "started" });
          event(response, { event: "tool.completed", tool: "fake-reader", duration: 0.25, error: false });
          // 하위 에이전트 사건은 도구 사건과 어미가 다르다. `.started` 와 `.completed` 가 아니다.
          // Hermes v0.21.0 은 여기에 session 번호를 싣지 않고 `preview` 만 보낸다.
          event(response, { event: "subagent.start", preview: "하위 에이전트가 찾기 시작했다" });
          event(response, { event: "subagent.complete", preview: "하위 에이전트가 찾기를 마쳤다" });
          event(response, { event: "run.completed" });
          response.end();
          return;
        }
      }

      if (request.method === "POST") {
        const match = RUN_PATH.exec(path);
        if (!match) return send(response, 404, { error: "not found" });
        const profile = match[1];
        if (!authorized(request, profile)) {
          return send(response, 401, { error: "bad key for this profile" });
        }
        submitCount += 1;
        // 한도에 닿은 gateway 는 본문을 읽기 전에 거절한다. 실행을 만들지 않는다.
        if (busy) return send(response, 429, RATE_LIMITED);

        const raw = await readBody(request);
        const submitted = (raw.length > 0 ? JSON.parse(raw) : {}) as {
          input?: string;
          instructions?: string;
          session_id?: string;
          provider?: string;
          model?: string;
        };
        lastSubmittedInstructions = submitted.instructions;
        lastSubmittedRuntime = { provider: submitted.provider, model: submitted.model };
        const runId = `run_${shortId()}`;
        const sessionId = submitted.session_id ?? `sess_${shortId()}`;

        // 실제 Hermes 는 provider 만 받으면 config 의 모델 문자열을 그대로 써서 실패한다.
        if (submitted.provider !== undefined && submitted.model === undefined) {
          runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider ?? null,
            error: "No LLM provider configured. Run `hermes model` to select a provider.",
            output: "",
            input: submitted.input ?? "",
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }

        // 그 provider 의 계정이 전부 막힌 상태다. 접수는 되고 나중에 failed 로 바뀐다.
        if (submitted.provider !== undefined && blockedProviders.has(submitted.provider)) {
          runs.set(runId, {
            run_id: runId,
            status: "failed",
            session_id: sessionId,
            model: submitted.model ?? profile!,
            provider: submitted.provider,
            error: PROVIDER_AUTH_FAILED,
            output: "",
            input: submitted.input ?? "",
            interruptEvents: false,
            usage: FAKE_USAGE,
          });
          return send(response, 200, { run_id: runId, status: "queued" });
        }
        const instructionsEcho =
          submitted.instructions && submitted.instructions.length > 0
            ? ` [instructions: ${submitted.instructions}]`
            : "";
        const held = holdNextRun;
        holdNextRun = false;
        runs.set(runId, {
          run_id: runId,
          status: held ? "running" : "completed",
          session_id: sessionId,
                  // 실제 Hermes 와 같이 요청 본문의 값을 그대로 되돌려 준다. 실제로 돈 모델이 아니다.
          model: submitted.model ?? profile!,
          output: specialOutputFor(submitted.input ?? "")
            ?? `[${who} on profile ${profile}]${instructionsEcho} ${submitted.input ?? ""}`,
          input: submitted.input ?? "",
          provider: submitted.provider ?? null,
          interruptEvents: submitted.input === "스트림 중단 검사",
          usage: FAKE_USAGE,
        });
        // 실제로 돈 모델은 세션 행에만 남는다.
        sessions.set(sessionId, {
          model: actualModelFor(submitted.input ?? "", submitted.model ?? profile!),
          provider:
            submitted.input === SESSION_MODEL_PROBE ? "nvidia" : submitted.provider ?? null,
        });
        if (held) {
          heldRunId = runId;
          heldRunWaiter?.();
        }
        return send(response, 200, { run_id: runId, status: "queued" });
      }

      const match = RUN_STATUS_PATH.exec(path);
      if (!match) return send(response, 404, { error: "not found" });
      const [, profile, runId] = match;
      if (!authorized(request, profile)) {
        return send(response, 401, { error: "bad key for this profile" });
      }
      const run = runs.get(runId);
      if (!run) return send(response, 404, { error: "no such run" });
      return send(response, 200, run);
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
        lastSubmittedInstructions: () => lastSubmittedInstructions,
        lastSubmittedRuntime: () => lastSubmittedRuntime,
        blockProvider: (provider: string) => blockedProviders.add(provider),
        clearBlockedProviders: () => blockedProviders.clear(),
        busy: () => {
          busy = true;
        },
        clearBusy: () => {
          busy = false;
        },
        submitCount: () => submitCount,
        holdNextRun: () => {
          holdNextRun = true;
          heldRunReady = new Promise<void>((done) => {
            heldRunWaiter = done;
          });
        },
        waitForHeldRun: () => heldRunReady ?? Promise.reject(new Error("유지할 실행을 먼저 지정해야 한다")),
        releaseHeldRun: () => {
          holdNextRun = false;
          heldRunWaiter?.();
          if (heldRunId === undefined) {
            heldRunReady = undefined;
            heldRunWaiter = undefined;
            return;
          }
          const run = runs.get(heldRunId);
          if (run !== undefined) run.status = "completed";
          heldRunId = undefined;
          heldRunReady = undefined;
          heldRunWaiter = undefined;
        },
        close: () =>
          new Promise<void>((done) => {
            holdNextRun = false;
            heldRunWaiter?.();
            heldRunReady = undefined;
            heldRunWaiter = undefined;
            server.closeAllConnections();
            server.close(() => done());
          }),
      });
    });
  });
}
