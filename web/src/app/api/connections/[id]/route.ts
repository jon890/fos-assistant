import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
  readValuesBody,
} from "@/lib/connection-route";
import type { ConnectorConnection } from "@/lib/connection";

type Context = { params: Promise<{ id: string }> };

export async function GET(_: Request, { params }: Context) {
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<ConnectorConnection>(
      `/api/v1/connections/${id}`,
      "connection",
    ),
  );
}

export async function POST(request: Request, { params }: Context) {
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  const values = await readValuesBody(request);
  if (!values) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<ConnectorConnection>(
      `/api/v1/connections/${id}`,
      "connection",
      {
        method: "POST",
        body: { values },
      },
    ),
  );
}

export async function DELETE(_: Request, { params }: Context) {
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<ConnectorConnection>(
      `/api/v1/connections/${id}`,
      "connection",
      {
        method: "DELETE",
      },
    ),
  );
}
