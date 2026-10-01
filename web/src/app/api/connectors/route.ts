import { connectionResponse, connectorCall } from "@/lib/connection-route";
import type { ConnectorSummary } from "@/lib/connection";

export async function GET() {
  return connectionResponse(
    await connectorCall<ConnectorSummary[]>("/api/v1/connectors", "catalog"),
  );
}
