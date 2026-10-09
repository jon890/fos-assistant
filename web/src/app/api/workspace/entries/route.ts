import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/** 디렉터리 하나의 목록이다. `path` 인자만 옮긴다. 없으면 Control Plane 이 사용자 디렉터리 자체를 읽는다. */
export async function GET(request: Request) {
  const path = new URL(request.url).searchParams.get("path");
  const query = path === null ? "" : `?path=${encodeURIComponent(path)}`;
  const result = await callControlPlane(`/api/v1/workspace/entries${query}`);
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}

/** 경로 하나를 지운다. 빈 경로는 사용자 디렉터리 자체라 Control Plane 에 보내지 않고 막는다. */
export async function DELETE(request: Request) {
  const path = new URL(request.url).searchParams.get("path");
  if (path === null || path === "")
    return errorResponse(
      "VALIDATION_FAILED",
      "지울 경로를 확인해 주세요.",
      400,
    );
  const result = await callControlPlane(
    `/api/v1/workspace/entries?path=${encodeURIComponent(path)}`,
    { method: "DELETE" },
  );
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
