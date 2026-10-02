import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

async function idOf(context: { params: Promise<{ id: string }> }): Promise<number | null> {
  const { id } = await context.params;
  return /^\d+$/.test(id) ? Number(id) : null;
}

function invalid() {
  return errorResponse("VALIDATION_FAILED", "기억 번호가 올바르지 않아요.", 400);
}

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok) return errorResponse(result.code, result.message, result.status);
  return result.data === null ? new NextResponse(null, { status: result.status }) : NextResponse.json(result.data);
}

export async function PATCH(request: Request, context: { params: Promise<{ id: string }> }) {
  const id = await idOf(context);
  if (id === null) return invalid();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return response(await callControlPlane(`/api/v1/memories/${id}`, { method: "PATCH", body: parsed.body }));
}

export async function DELETE(_request: Request, context: { params: Promise<{ id: string }> }) {
  const id = await idOf(context);
  return id === null ? invalid() : response(await callControlPlane(`/api/v1/memories/${id}`, { method: "DELETE" }));
}
