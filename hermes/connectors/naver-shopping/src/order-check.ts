import { logDiagnostic, ToolError } from "./errors.ts";
import { ORDER_HISTORY_STATE } from "./expressions.ts";
import {
  matchOrder,
  mismatchReason,
  ORDER_MESSAGES,
  OrdersChanged,
  parsePaymentContext,
  readOrders,
  type OrderCheck,
} from "./parse-orders.ts";
import { browserUrl, type Env } from "./session.ts";
import { readInTab, type TabOptions } from "./tab.ts";
import { ORDER_HISTORY_URL } from "./urls.ts";
import { parseJson } from "./values.ts";

export const ORDER_CHECK_TOOL = "check_shopping_order";

export type OrderCheckDeps = { read: typeof readInTab; tab?: TabOptions };
const DEFAULT_DEPS: OrderCheckDeps = { read: readInTab };

/**
 * 결제 화면을 넘긴 뒤의 주문을 결제 내역에서 확인한다. 넘긴 시각과 금액, 상품 주소는 모델 인자가 아니라 fos-ctx 가 넣은
 * `_fos_payment` 에서만 읽는다. 맥락이 없으면 화면을 열지 않는다. 여는 주소는 상수 `ORDER_HISTORY_URL` 하나다.
 */
export async function checkOrder(
  env: Env,
  payment: unknown,
  deps: OrderCheckDeps = DEFAULT_DEPS,
): Promise<OrderCheck> {
  const context = parsePaymentContext(payment);
  if (context === null)
    return {
      status: "NO_PAYMENT_HANDOFF",
      message: ORDER_MESSAGES.NO_PAYMENT_HANDOFF,
    };
  const cdpUrl = browserUrl(env);
  // 읽기는 탭 안에서 한다. 칸이 없으면 탭을 남긴 예상 밖 화면 넘기기가 된다.
  return deps.read(
    cdpUrl,
    ORDER_HISTORY_URL,
    ORDER_HISTORY_STATE,
    (raw) => {
      try {
        const orders = readOrders(parseJson(raw) ?? null);
        const result = matchOrder(orders, context);
        // 진단 줄에는 결과 낱말과 까닭 낱말만 싣는다. 금액, 시각, 상품 번호는 싣지 않는다.
        const reason = mismatchReason(orders, context);
        logDiagnostic(
          ORDER_CHECK_TOOL,
          `match.${result.status.toLowerCase()}`,
          reason === null ? [] : [reason],
        );
        return result;
      } catch (error) {
        if (!(error instanceof OrdersChanged)) throw error;
        logDiagnostic(ORDER_CHECK_TOOL, "parse", [error.field]);
        throw new ToolError("NAVER_SHOPPING_PAGE_CHANGED");
      }
    },
    deps.tab,
  );
}
