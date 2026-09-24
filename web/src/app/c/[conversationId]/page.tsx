import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";

export default async function ConversationPage({ params }: {
  params: Promise<{ conversationId: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { conversationId } = await params;
  if (!/^\d+$/.test(conversationId)) redirect("/");
  return <ChatPanel initialConversationId={Number(conversationId)} />;
}
