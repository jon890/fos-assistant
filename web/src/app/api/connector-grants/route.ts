import {
  connectorActionCall,
  connectorActionResponse,
} from "@/lib/connector-action-route";
import type { ConnectorGrant } from "@/lib/connector-action";

/** 현재 사용자가 묻지 않고 실행하게 허락한 도구를 읽는다. */
export async function GET() {
  return connectorActionResponse(
    await connectorActionCall<ConnectorGrant[]>(
      "/api/v1/connector-grants",
      "grants",
    ),
  );
}
