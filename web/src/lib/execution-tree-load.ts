import type { ExecutionTreeResponse } from "@/components/execution/execution-tree";
import { fetchExecutionTree } from "./usage-api.ts";

/** 첫 실패 뒤 다시 읽기까지 기다리는 시간(ms)이다. */
const RETRY_DELAY_MS = 1_000;

/**
 * 실행 트리를 읽지 못한 까닭이다.
 *
 * <p>`reason` 은 다음에 실패를 보고 원인을 구분하려는 값이다. HTTP 상태와 오류 code, 또는 연결 실패와 본문 파싱
 * 실패를 적는다. 사용자에게 보이는 문구가 아니다.
 */
export class ExecutionTreeLoadError extends Error {
  readonly reason: string;
  /** 다시 읽어 볼 만한 실패인가. 세션 만료(401)나 없는 실행(404) 같은 4xx 는 다시 읽어도 같다 */
  readonly retryable: boolean;

  constructor(reason: string, retryable = true) {
    super(`실행 트리를 읽지 못했다 reason=${reason}`);
    this.name = "ExecutionTreeLoadError";
    this.reason = reason;
    this.retryable = retryable;
  }
}

/** 4xx 가운데 기다리면 풀릴 수 있는 요청 시간 초과(408)와 요청 제한(429)만 다시 읽는다. */
function retryableStatus(status: number): boolean {
  return status >= 500 || status === 408 || status === 429;
}

type Options = {
  /** 실행 트리를 부르는 함수. 테스트가 바꿔 넣는다 */
  fetchTree?: (executionId: number) => Promise<Response>;
  /** 다시 읽기 전에 기다린다. 테스트가 바꿔 넣는다 */
  wait?: (ms: number) => Promise<void>;
  /** 읽기를 그만둘지 묻는다. 화면이 사라졌으면 다시 읽지 않는다 */
  stopped?: () => boolean;
};

async function errorCode(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { code?: unknown };
    return typeof body.code === "string" ? body.code : null;
  } catch {
    return null;
  }
}

async function readOnce(
  executionId: number,
  fetchTree: (executionId: number) => Promise<Response>,
): Promise<ExecutionTreeResponse> {
  let response: Response;
  try {
    response = await fetchTree(executionId);
  } catch (error) {
    throw new ExecutionTreeLoadError(
      `network ${error instanceof Error ? error.name : "unknown"}`,
    );
  }
  if (!response.ok) {
    const code = await errorCode(response);
    throw new ExecutionTreeLoadError(
      `http ${response.status}${code ? ` ${code}` : ""}`,
      retryableStatus(response.status),
    );
  }
  let tree: ExecutionTreeResponse;
  try {
    tree = (await response.json()) as ExecutionTreeResponse;
  } catch {
    throw new ExecutionTreeLoadError("parse body");
  }
  if (!tree?.root || !Array.isArray(tree.root.events)) {
    throw new ExecutionTreeLoadError("parse shape");
  }
  return tree;
}

/**
 * 끝난 답의 작업 과정에 쓸 실행 트리를 읽는다. 처음 읽기가 실패하면 한 번만 다시 읽는다.
 *
 * <p>운영에서 오래 쉰 뒤 첫 조회가 느리거나 일시적으로 실패해, 펼친 작업 과정이 「읽지 못했어요」 로 끝난 일이
 * 있었다. 같은 조회를 곧바로 다시 하면 읽힌다. 다시 읽어도 같은 4xx(세션 만료, 없는 실행)는 곧바로 던진다. 두 번 모두
 * 실패하면 마지막 까닭을 콘솔에 남기고 던진다.
 */
export async function loadExecutionTree(
  executionId: number,
  options: Options = {},
): Promise<ExecutionTreeResponse> {
  const fetchTree = options.fetchTree ?? fetchExecutionTree;
  const wait =
    options.wait ??
    ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  try {
    return await readOnce(executionId, fetchTree);
  } catch (first) {
    console.warn(
      `작업 과정 첫 읽기 실패 executionId=${executionId}`,
      first instanceof ExecutionTreeLoadError ? first.reason : first,
    );
    if (first instanceof ExecutionTreeLoadError && !first.retryable)
      throw first;
    await wait(RETRY_DELAY_MS);
    if (options.stopped?.()) throw first;
  }
  try {
    return await readOnce(executionId, fetchTree);
  } catch (second) {
    console.warn(
      `작업 과정 다시 읽기 실패 executionId=${executionId}`,
      second instanceof ExecutionTreeLoadError ? second.reason : second,
    );
    throw second;
  }
}
