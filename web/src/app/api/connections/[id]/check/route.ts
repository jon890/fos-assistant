import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
} from "@/lib/connection-route";
import type { ConnectorConnection } from "@/lib/connection";

export async function POST(
  _: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<ConnectorConnection>(
      `/api/v1/connections/${id}/check`,
      "connection",
      {
        method: "POST",
      },
    ),
  );
}
