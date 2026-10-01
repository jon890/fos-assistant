import { connectionResponse, connectorCall } from "@/lib/connection-route";
import type { AdminConnection } from "@/lib/connection";

export async function GET() {
  return connectionResponse(
    await connectorCall<AdminConnection[]>(
      "/api/v1/admin/connections",
      "admin",
    ),
  );
}
