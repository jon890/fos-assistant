import { decideMemoryLinkRoute } from "@/lib/memory-link-route";

export async function POST(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  return decideMemoryLinkRoute(context, "revoke");
}
