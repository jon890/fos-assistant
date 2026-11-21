import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { errorResponse } from "@/lib/api-response";
import { readJsonBody } from "@/lib/json-body";
import { rejectCrossOrigin } from "@/lib/same-origin";

/** 관리자. 웹 푸시 VAPID 키를 서버가 새로 만든다. 본문의 `subject` 가 비면 Control Plane 이 만드는 관리자의 이메일을 쓴다. */
export async function POST(request: Request) {
  const rejected = rejectCrossOrigin(request);
  if (rejected) return rejected;
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  const { subject, expectedPublicKey } = parsed.body;
  const result = await callControlPlane(
    "/api/v1/admin/secrets/vapid/regenerate",
    {
      method: "POST",
      body: {
        subject: typeof subject === "string" ? subject : undefined,
        expectedPublicKey:
          typeof expectedPublicKey === "string" ? expectedPublicKey : null,
      },
    },
  );
  return result.ok
    ? NextResponse.json(result.data)
    : errorResponse(result.code, result.message, result.status);
}
