import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";

/** 새 대화 화면이다. 늘 기본 비서와 시작한다(docs/features/assistant-hub/spec.md 의 REQ4). */
export default async function NewConversationPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return <ChatPanel initialConversationId={null} />;
}
