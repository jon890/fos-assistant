import type { Operation } from "./api-contract.ts";
import {
  conditionalOperation,
  conditionalResult,
  isConditionalWrite,
} from "./conditional-result.ts";
import { TossinvestError } from "./errors.ts";
import type { ClaimedApproval } from "./execution-guard.ts";
import { clock } from "./rate-limit.ts";
import {
  financialFailure,
  financialOperation,
  financialResult,
} from "./trading-body.ts";
import type { Exchange } from "./transport.ts";

/** client 가 넘기는 금융 전송 수단이다. 토큰 identity 와 quota 는 client 안에 남는다. */
export interface FinancialContext {
  timeoutMs: number;
  account: string;
  getToken(deadlineMs: number): Promise<string>;
  exchange(
    operation: Operation,
    path: string,
    init: RequestInit,
    deadlineMs: number,
    beforeSend: () => void,
  ): Promise<Exchange>;
}

/** 기존 callback 은 소스 호환으로 받는다. 새 호출부는 검증된 ticket deadline 을 넘긴다. */
export type FinancialAuthorization = (() => void) | ClaimedApproval;

/**
 * 금융 요청을 한 번만 보낸다. GET 재시도와 토큰 재발급 루프에 들어가지 않는다.
 * deadline 은 요청 제한과 ticket 남은 시간 중 짧은 값이며 auth·quota·fetch·본문 읽기에 같이 적용한다.
 * 조건주문 세 경로만 조건 분기로 보내고 나머지는 일반 주문 분기를 그대로 쓴다.
 */
export async function executeFinancial(
  context: FinancialContext,
  method: "POST" | "DELETE",
  path: string,
  body: unknown,
  authorization: FinancialAuthorization,
): Promise<any> {
  const conditional = isConditionalWrite(method, path);
  const operation = conditional
    ? conditionalOperation(method, path)
    : financialOperation(method, path);
  const legacy = typeof authorization === "function";
  const check = legacy ? authorization : authorization.beforeSend;
  const limit = clock.now() + context.timeoutMs;
  const deadline = legacy ? limit : Math.min(limit, authorization.deadlineMs);
  let started = false;
  try {
    if (clock.now() >= deadline)
      throw new TossinvestError("TOSSINVEST_NETWORK");
    const token = await context.getToken(deadline);
    const response = await context.exchange(
      operation,
      path,
      {
        method,
        headers: {
          authorization: `Bearer ${token}`,
          accept: "application/json",
          "content-type": "application/json",
          "x-tossinvest-account": context.account,
        },
        body: method === "DELETE" ? undefined : JSON.stringify(body),
      },
      deadline,
      () => {
        check();
        // 큐 대기 뒤 callback 검증을 지나도 남은 시간이 없으면 시작하지 않는다.
        if (clock.now() >= deadline)
          throw new TossinvestError("TOSSINVEST_NETWORK");
        started = true;
      },
    );
    return conditional
      ? conditionalResult(operation.id, response)
      : financialResult(operation.id, response);
  } catch (error) {
    financialFailure(error, started);
  }
}
