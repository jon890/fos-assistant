import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { CONNECTOR_ID_PATTERN } from "@/lib/connection";
import { readJsonBody } from "@/lib/json-body";
import { rejectCrossOrigin } from "@/lib/same-origin";

type Context = { params: Promise<{ connectorId: string }> };

function invalid() {
  return errorResponse("VALIDATION_FAILED", "입력 형식을 확인해 주세요.", 400);
}

/** 관리자. 그 플러그인의 OAuth 앱 client ID, client secret, App 이름을 저장한다. 공유 앱은 같은 제공자의 플러그인이 함께 쓴다. */
export async function PUT(request: Request, { params }: Context) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const { connectorId } = await params;
  if (!CONNECTOR_ID_PATTERN.test(connectorId)) return invalid();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { clientId, clientSecret, appSlug } = parsed.body;
  if (typeof clientId !== "string" || typeof clientSecret !== "string")
    return invalid();
  if (appSlug !== undefined && appSlug !== null && typeof appSlug !== "string")
    return invalid();
  const result = await callControlPlane(
    `/api/v1/admin/secrets/connector-oauth/apps/${connectorId}`,
    {
      method: "PUT",
      body: { clientId, clientSecret, appSlug: appSlug ?? null },
    },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}

/** 관리자. 그 OAuth 앱 설정을 비운다. 비우면 그 앱을 쓰는 플러그인의 단추가 사라진다. */
export async function DELETE(request: Request, { params }: Context) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const { connectorId } = await params;
  if (!CONNECTOR_ID_PATTERN.test(connectorId)) return invalid();
  const result = await callControlPlane(
    `/api/v1/admin/secrets/connector-oauth/apps/${connectorId}`,
    {
      method: "DELETE",
    },
  );
  return result.ok
    ? new NextResponse(null, { status: 204 })
    : errorResponse(result.code, result.message, result.status);
}
