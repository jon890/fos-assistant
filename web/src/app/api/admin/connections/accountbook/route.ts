import { connectorCall, connectionResponse } from "@/lib/connection-route";
import type { AdminAccountbookConnection } from "@/lib/connection";

export async function GET() {
  return connectionResponse(await connectorCall<AdminAccountbookConnection[]>("/api/v1/admin/connections/accountbook"));
}
