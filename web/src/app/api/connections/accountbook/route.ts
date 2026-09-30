import { connectorCall, connectionResponse, invalidConnectionRequest, readConnectionBody } from "@/lib/connection-route";
import type { AccountbookConnection } from "@/lib/connection";

export async function GET() {
  return connectionResponse(await connectorCall<AccountbookConnection>("/api/v1/connections/accountbook"));
}

export async function POST(request: Request) {
  const body = await readConnectionBody(request);
  if (!body) return invalidConnectionRequest();
  return connectionResponse(await connectorCall<AccountbookConnection>("/api/v1/connections/accountbook", { method: "POST", body }));
}

export async function DELETE() {
  return connectionResponse(await connectorCall<AccountbookConnection>("/api/v1/connections/accountbook", { method: "DELETE" }));
}
