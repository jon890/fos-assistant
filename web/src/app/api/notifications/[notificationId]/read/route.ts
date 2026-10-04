import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";
import { errorResponse } from "@/lib/api-response";

type RouteContext = {
  params: Promise<{ notificationId: string }>;
};

/** 알림 하나를 읽음으로 표시한다. 식별자는 UUID 모양의 공개 식별자다. */
export async function POST(_request: Request, context: RouteContext) {
  const { notificationId } = await context.params;
  if (!isPublicId(notificationId)) {
    return errorResponse(
      "VALIDATION_FAILED",
      "알림 주소가 올바르지 않아요.",
      400,
    );
  }

  const result = await callControlPlane<unknown>(
    `/api/v1/notifications/${notificationId}/read`,
    { method: "POST" },
  );
  if (!result.ok) {
    return errorResponse(result.code, result.message, result.status);
  }
  return NextResponse.json(result.data);
}
