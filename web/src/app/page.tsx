import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";

export default async function HomePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return <ChatPanel />;
}
