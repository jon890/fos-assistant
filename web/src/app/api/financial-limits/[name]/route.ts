import {
  connectorActionResponse,
  invalidConnectorActionRequest,
} from "@/lib/connector-action-route";
import {
  isFinancialLimitName,
  LIMIT_VALUE_PATTERN,
} from "@/lib/financial-limits";
import { financialLimitsCall } from "@/lib/financial-limits-route";
import { readJsonBody } from "@/lib/json-body";

type Context = { params: Promise<{ name: string }> };

/**
 * 상한 하나를 바꾼다. `value` 가 null 이면 운영 최대치를 따르게 한다. 계좌 주인의 직접 조작이라 승인 카드를 만들지
 * 않고, 다시 확인은 화면이 한다. 이름과 값의 모양이 틀리면 Control Plane 을 부르지 않는다.
 */
export async function PUT(request: Request, { params }: Context) {
  const { name } = await params;
  if (!isFinancialLimitName(name)) return invalidConnectorActionRequest();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return invalidConnectorActionRequest();
  const value = parsed.body.value;
  if (
    value !== null &&
    (typeof value !== "string" || !LIMIT_VALUE_PATTERN.test(value))
  )
    return invalidConnectorActionRequest();
  return connectorActionResponse(
    await financialLimitsCall(`/api/v1/financial-limits/${name}`, {
      method: "PUT",
      body: { value },
    }),
  );
}
