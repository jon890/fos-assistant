import { loadToolsetRequest } from "@/components/agent/toolset-request-page";

export default async function ToolsetRequestPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return loadToolsetRequest(id, false);
}
