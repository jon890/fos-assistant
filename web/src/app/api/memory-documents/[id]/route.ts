import { NextResponse } from "next/server";
import { errorResponse } from "@/lib/api-response";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";

async function idOf(context: {
  params: Promise<{ id: string }>;
}): Promise<number | null> {
  const { id } = await context.params;
  return /^\d+$/.test(id) ? Number(id) : null;
}

function invalid() {
  return errorResponse(
    "VALIDATION_FAILED",
    "문서 번호가 올바르지 않아요.",
    400,
  );
}

/** 본문이 실리므로 어떤 캐시에도 남기지 않는다. */
function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return NextResponse.json(result.data, {
    headers: { "Cache-Control": "no-store" },
  });
}

export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const id = await idOf(context);
  return id === null
    ? invalid()
    : response(await callControlPlane(`/api/v1/memory-documents/${id}`));
}

export async function PUT(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const id = await idOf(context);
  if (id === null) return invalid();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return response(
    await callControlPlane(`/api/v1/memory-documents/${id}`, {
      method: "PUT",
      body: parsed.body,
    }),
  );
}
