import { agentToolsRoute } from "@/lib/agent-tools-route";

type RouteContext = { params: Promise<{ code: string }> };

export async function GET(request: Request, context: RouteContext) {
  return agentToolsRoute(request, context, true);
}

export async function PUT(request: Request, context: RouteContext) {
  return agentToolsRoute(request, context, true);
}
