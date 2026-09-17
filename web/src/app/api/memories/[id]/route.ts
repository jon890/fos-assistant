import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";

async function idOf(context: { params: Promise<{ id: string }> }): Promise<number | null> {
  const { id } = await context.params;
  return /^\d+$/.test(id) ? Number(id) : null;
}

function invalid() {
  return NextResponse.json({ code: "VALIDATION_FAILED", message: "Memory 번호가 올바르지 않습니다." }, { status: 400 });
}

function response(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok) return NextResponse.json({ code: result.code, message: result.message }, { status: result.status });
  return result.data === null ? new NextResponse(null, { status: result.status }) : NextResponse.json(result.data);
}

export async function PATCH(request: Request, context: { params: Promise<{ id: string }> }) {
  const id = await idOf(context);
  return id === null ? invalid() : response(await callControlPlane(`/api/v1/memories/${id}`, { method: "PATCH", body: await request.json() }));
}

export async function DELETE(_request: Request, context: { params: Promise<{ id: string }> }) {
  const id = await idOf(context);
  return id === null ? invalid() : response(await callControlPlane(`/api/v1/memories/${id}`, { method: "DELETE" }));
}
