/**
 * 도는 turn 의 run 이 받은 session 으로 MCP `agent_*` 도구를 부르는 시나리오들이 함께 쓰는 도우미다.
 *
 * <p>시나리오가 profile 플러그인 역할을 한다. 붙잡아 둔 turn 의 session 을 뿌리로 `_fos_ctx` 를 계약대로 서명해 `/mcp` 를 직접 부른다.
 */
import { randomUUID } from "node:crypto";
import { call, expect, expectStatus, fail, type Context } from "./harness.ts";
import { readEventStream } from "../../web/src/lib/stream.ts";
import { signedCallContext, type FosCallContext } from "./mcp-context.ts";

export type ChatEvent = { type: string; executionId?: number; conversationId?: string };
export type ToolResult = { isError: boolean; text: string };
export type Status = { execution_id: number; status: string; output?: string; error_code?: string; stop_requested?: boolean };
export type TreeNode = {
  executionId: number;
  status: string;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  children: TreeNode[];
};
export type Tree = { root: TreeNode; truncated: boolean };

export function within<T>(promise: Promise<T>, milliseconds: number, message: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return Promise.race([
    promise,
    new Promise<T>((_, reject) => {
      timer = setTimeout(() => reject(new Error(message)), milliseconds);
    }),
  ]).finally(() => {
    if (timer !== undefined) clearTimeout(timer);
  });
}

/** 스트림으로 turn 하나를 열고 `started` 사건의 실행 번호와 스트림이 끝나는 약속을 돌려준다. */
export async function openStream(
  context: Context,
  text: string,
  agentCode: string,
  conversationId?: string,
): Promise<{ started: Promise<ChatEvent>; executionId: Promise<number>; completed: Promise<ChatEvent[]> }> {
  const response = await fetch(`${context.api}/chat/messages/stream`, {
    method: "POST",
    headers: { Authorization: `Bearer ${context.tokens.dad}`, "Content-Type": "application/json" },
    body: JSON.stringify({ text, agentCode, conversationId }),
  });
  expect(response.status === 200, `검사 turn 의 스트림을 열지 못했다: ${response.status}`);
  const received: ChatEvent[] = [];
  let resolveStarted: (event: ChatEvent) => void;
  let rejectStarted: (error: Error) => void;
  const started = new Promise<ChatEvent>((resolve, reject) => {
    resolveStarted = resolve;
    rejectStarted = reject;
  });
  const executionId = started.then((event) => event.executionId!);
  const completed = readEventStream<ChatEvent>(response, (event) => {
    received.push(event);
    if (event.type === "started" && event.executionId !== undefined) resolveStarted!(event);
  }).then(
    () => {
      rejectStarted!(new Error("started 사건 없이 스트림이 끝났다"));
      return received;
    },
    (error: unknown) => {
      rejectStarted!(error instanceof Error ? error : new Error(String(error)));
      throw error;
    },
  );
  // 단계가 먼저 실패해 어느 한쪽을 기다리지 않게 돼도 처리되지 않은 거절로 남아 프로세스가 끝나지 않게 한다.
  completed.catch(() => undefined);
  started.catch(() => undefined);
  executionId.catch(() => undefined);
  return { started, executionId, completed };
}

/** 플러그인처럼 서명한 `_fos_ctx` 를 붙여 도구 하나를 부르고 도구 결과를 돌려준다. */
export async function callTool(
  context: Context,
  token: string,
  name: string,
  args: Record<string, unknown>,
  fosCtx: FosCallContext,
): Promise<ToolResult> {
  const response = await fetch(context.api.replace(/\/api\/v1$/, "") + "/mcp", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: { ...args, _fos_ctx: fosCtx } } }),
  });
  expect(response.status === 200, `${name} 의 HTTP 상태가 200 이 아니다: ${response.status}`);
  const body = await response.json() as { result?: { isError?: boolean; content?: { text?: string }[] } };
  const text = body.result?.content?.[0]?.text;
  if (text === undefined) fail(`${name} 의 도구 결과에 글이 없다: ${JSON.stringify(body)}`);
  return { isError: body.result?.isError === true, text };
}

/** 뿌리 session 에서 부른 것처럼 서명한다. 도구 호출 id 를 주지 않으면 새로 만든다. */
export function contextFor(token: string, name: string, rootSession: string, toolCallId = `call_${randomUUID()}`): FosCallContext {
  return signedCallContext(token, name, rootSession, rootSession, toolCallId);
}

export function parsed<T>(result: ToolResult, what: string): T {
  expect(!result.isError, `${what} 가 실패했다: ${result.text}`);
  return JSON.parse(result.text) as T;
}

/** 조건을 만족하는 상태가 올 때까지 `agent_status` 를 다시 부른다. */
export async function awaitStatus(
  read: () => Promise<Status>,
  done: (status: Status) => boolean,
  what: string,
): Promise<Status> {
  const deadline = Date.now() + 10_000;
  let last: Status | undefined;
  while (Date.now() < deadline) {
    last = await read();
    if (done(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail(`${what}: 10초 안에 기대한 상태가 오지 않았다. 마지막 상태: ${JSON.stringify(last)}`);
}

export async function tree(context: Context, rootExecutionId: number): Promise<Tree> {
  return expectStatus(
    await call(context, `/usage/executions/${rootExecutionId}/tree`, { token: context.tokens.dad }),
    200,
    "위임 검사 실행 나무",
  ).json<Tree>();
}
