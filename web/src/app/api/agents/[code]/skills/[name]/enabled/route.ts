import { skillEnabledRoute } from "@/lib/skill-enabled-route";

export async function PUT(
  request: Request,
  context: { params: Promise<{ code: string; name: string }> },
) {
  return skillEnabledRoute(request, context, false);
}
