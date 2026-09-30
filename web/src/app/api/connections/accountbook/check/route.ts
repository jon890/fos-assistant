import { connectorCall, connectionResponse } from "@/lib/connection-route";
import type { AccountbookConnection } from "@/lib/connection";

export async function POST() {
  return connectionResponse(await connectorCall<AccountbookConnection>("/api/v1/connections/accountbook/check", { method: "POST" }));
}
