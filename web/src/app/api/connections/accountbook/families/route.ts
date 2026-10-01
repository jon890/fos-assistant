import { connectorCall, connectionResponse, invalidConnectionRequest, readConnectionBody } from "@/lib/connection-route";
import type { AccountbookFamily } from "@/lib/connection";

export async function POST(request: Request) {
  const body = await readConnectionBody(request);
  if (!body) return invalidConnectionRequest();
  return connectionResponse(await connectorCall<AccountbookFamily[]>("/api/v1/connections/accountbook/families", {
    method: "POST", body: { token: body.token },
  }));
}
