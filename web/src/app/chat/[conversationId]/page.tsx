import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";
import { isConversationId } from "@/lib/conversation-id";

export default async function ConversationPage({ params }: {
  params: Promise<{ conversationId: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { conversationId } = await params;
  if (!isConversationId(conversationId)) redirect("/");
  // 사이드바가 주소를 소문자 식별자와 그대로 비교하므로 대문자로 열어도 소문자 주소로 옮긴다.
  const normalized = conversationId.toLowerCase();
  if (normalized !== conversationId) redirect(`/chat/${normalized}`);
  return <ChatPanel initialConversationId={conversationId} />;
}
