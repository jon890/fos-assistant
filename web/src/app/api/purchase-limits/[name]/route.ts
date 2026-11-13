import {
  connectorActionResponse,
  invalidConnectorActionRequest,
} from "@/lib/connector-action-route";
import { readJsonBody } from "@/lib/json-body";
import {
  isPurchaseLimitName,
  isPurchaseLimitValue,
} from "@/lib/purchase-limits";
import { purchaseLimitsCall } from "@/lib/purchase-limits-route";

type Context = { params: Promise<{ name: string }> };

/**
 * 구매 상한 하나를 바꾼다. 사용자의 직접 조작이라 승인 카드를 만들지 않고, 다시 확인은 화면이 한다.
 * 이름과 값의 모양이 틀리면 Control Plane 을 부르지 않는다. 값은 0 이상 1조 미만의 원 단위 정수다.
 */
export async function PUT(request: Request, { params }: Context) {
  const { name } = await params;
  if (!isPurchaseLimitName(name)) return invalidConnectorActionRequest();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return invalidConnectorActionRequest();
  const value = parsed.body.value;
  if (!isPurchaseLimitValue(value)) return invalidConnectorActionRequest();
  return connectorActionResponse(
    await purchaseLimitsCall(`/api/v1/purchase-limits/${name}`, {
      method: "PUT",
      body: { value },
    }),
  );
}
