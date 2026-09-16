import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { ChatPanel } from "@/components/chat-panel";

export default async function HomePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }

  return (
    <>
      <h1 className="mb-4 text-lg font-semibold">대화</h1>
      <ChatPanel />
    </>
  );
}
