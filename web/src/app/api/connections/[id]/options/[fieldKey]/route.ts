import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
  isFieldKey,
  readValuesBody,
} from "@/lib/connection-route";
import type { ConnectorOption } from "@/lib/connection";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string; fieldKey: string }> },
) {
  const { id, fieldKey } = await params;
  if (!isConnectorId(id) || !isFieldKey(fieldKey))
    return invalidConnectionRequest();
  const values = await readValuesBody(request);
  if (!values) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<ConnectorOption[]>(
      `/api/v1/connections/${id}/options/${fieldKey}`,
      "options",
      { method: "POST", body: { values } },
    ),
  );
}
