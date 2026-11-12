import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";

/** 새 대화 화면이다. `?agent=<code>` 로 처음 고를 에이전트를 받는다. */
export default async function NewConversationPage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return <ChatPanel initialConversationId={null} />;
}
