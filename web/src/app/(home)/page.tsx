import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";
import { NEW_CONVERSATION_PATH } from "@/lib/room";

/**
 * 기본 비서와 이어지는 대화방을 열어 그 대화 주소로 넘긴다. 화면은 그리지 않는다(docs/features/agent-team.md 의 「대화방」).
 *
 * <p>처음 열면 Control Plane 이 빈 대화방을 만든다. 기본 비서가 없는 사용자는 새 대화 화면으로 보낸다. 그 밖의 실패는 새 대화
 * 화면으로 덮지 않고 throw 해서 Next 오류 화면으로 드러낸다.
 */
export default async function HomePage() {
  const session = await auth();
  if (!session?.user?.email) {
    redirect("/signin");
  }
  const result = await callControlPlane<{ id: string }>(
    "/api/v1/chat/conversations/room",
    { method: "POST" },
  );
  if (!result.ok) {
    if (result.code === "AGENT_NOT_FOUND") redirect(NEW_CONVERSATION_PATH);
    throw new Error(
      `대화방을 열지 못했다: ${result.status} ${result.code} ${result.message}`,
    );
  }
  redirect(`/chat/${result.data.id}`);
}
