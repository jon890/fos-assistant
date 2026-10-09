import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";

/**
 * 실행 공간의 디렉터리 하나의 목록이다. `path` 인자만 옮기고, 경로 검사는 Control Plane 이 한다.
 *
 * <p>인자가 없으면 붙이지 않는다. Control Plane 은 그때 사용자 디렉터리 자체를 읽는다.
 */
export async function GET(request: Request) {
  const path = new URL(request.url).searchParams.get("path");
  const query = path === null ? "" : `?path=${encodeURIComponent(path)}`;
  const result = await callControlPlane(`/api/v1/workspace/entries${query}`);
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data);
}
