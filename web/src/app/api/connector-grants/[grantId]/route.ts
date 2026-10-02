import {
  connectorActionCall,
  connectorActionResponse,
  invalidConnectorActionRequest,
  isGrantId,
} from "@/lib/connector-action-route";

type Context = { params: Promise<{ grantId: string }> };

/** 허락을 거둔다. 그 뒤로는 그 도구를 부를 때마다 다시 묻는다. */
export async function DELETE(_: Request, { params }: Context) {
  const { grantId } = await params;
  if (!isGrantId(grantId)) return invalidConnectorActionRequest();
  return connectorActionResponse(
    await connectorActionCall<null>(
      `/api/v1/connector-grants/${grantId}`,
      "none",
      { method: "DELETE" },
    ),
  );
}
