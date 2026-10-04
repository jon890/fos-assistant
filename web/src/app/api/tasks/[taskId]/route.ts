import { NextResponse } from "next/server";
import { callControlPlane } from "@/lib/control-plane";
import { isPublicId } from "@/lib/conversation-id";
import { readJsonBody } from "@/lib/json-body";
import { errorResponse } from "@/lib/api-response";

type RouteContext = { params: Promise<{ taskId: string }> };

async function idOf(context: RouteContext): Promise<string | null> {
  const { taskId } = await context.params;
  return isPublicId(taskId) ? taskId : null;
}

function invalid() {
  return errorResponse(
    "VALIDATION_FAILED",
    "작업 주소가 올바르지 않아요.",
    400,
  );
}

function respond(result: Awaited<ReturnType<typeof callControlPlane>>) {
  if (!result.ok)
    return errorResponse(result.code, result.message, result.status);
  return result.data === null
    ? new NextResponse(null, { status: result.status })
    : NextResponse.json(result.data);
}

export async function GET(_request: Request, context: RouteContext) {
  const id = await idOf(context);
  return id === null
    ? invalid()
    : respond(await callControlPlane(`/api/v1/tasks/${id}`));
}

export async function PUT(request: Request, context: RouteContext) {
  const id = await idOf(context);
  if (id === null) return invalid();
  const parsed = await readJsonBody(request);
  if (!parsed.ok) return parsed.response;
  return respond(
    await callControlPlane(`/api/v1/tasks/${id}`, {
      method: "PUT",
      body: parsed.body,
    }),
  );
}

export async function DELETE(_request: Request, context: RouteContext) {
  const id = await idOf(context);
  return id === null
    ? invalid()
    : respond(
        await callControlPlane(`/api/v1/tasks/${id}`, { method: "DELETE" }),
      );
}
