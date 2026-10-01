import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
} from "@/lib/connection-route";
import type { AdminConnection } from "@/lib/connection";

export async function POST(
  _: Request,
  { params }: { params: Promise<{ id: string; userId: string }> },
) {
  const { id, userId } = await params;
  if (!isConnectorId(id) || !/^\d+$/.test(userId) || Number(userId) < 1) {
    return invalidConnectionRequest();
  }
  return connectionResponse(
    await connectorCall<AdminConnection>(
      `/api/v1/admin/connections/${id}/${userId}/confirm`,
      "adminItem",
      { method: "POST" },
    ),
  );
}
