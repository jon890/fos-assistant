import {
  connectionResponse,
  connectorCall,
  invalidConnectionRequest,
  isConnectorId,
} from "@/lib/connection-route";
import { readJsonBody } from "@/lib/json-body";
import type { SpecialistAgentResult } from "@/lib/specialist-agent";

/** 글이거나 비었으면 그대로, 아니면 거절한다. 길이와 목록 판정은 Control Plane 이 한다. */
function optionalText(value: unknown): string | null | undefined {
  if (value === null || value === undefined) return null;
  return typeof value === "string" ? value : undefined;
}

/** 전문 에이전트를 만든다. 요청 본문은 정해진 칸만 옮긴다. */
export async function POST(
  request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!isConnectorId(id)) return invalidConnectionRequest();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return invalidConnectionRequest();
  const body = parsed.body;
  const name = optionalText(body.name);
  const description = optionalText(body.description);
  const icon = optionalText(body.icon);
  const color = optionalText(body.color);
  if (
    name === undefined ||
    description === undefined ||
    icon === undefined ||
    color === undefined ||
    typeof body.detach !== "boolean"
  )
    return invalidConnectionRequest();
  return connectionResponse(
    await connectorCall<SpecialistAgentResult>(
      `/api/v1/connections/${id}/specialist`,
      "specialist",
      {
        method: "POST",
        body: { name, description, icon, color, detach: body.detach },
      },
    ),
  );
}
