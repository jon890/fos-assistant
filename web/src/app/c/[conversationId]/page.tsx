import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";

/**
 * 대화 번호로 된 옛 주소를 공개 식별자 주소로 넘겨 준다. 화면은 그리지 않는다.
 *
 * <p>Control Plane 이 요청자의 대화일 때만 식별자를 돌려준다. 없는 대화와 남의 대화는 가리지 않고 `/` 로 보낸다.
 */
export default async function LegacyConversationPage({ params }: {
  params: Promise<{ conversationId: string }>;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  const { conversationId } = await params;
  if (!/^\d+$/.test(conversationId)) redirect("/");
  const result = await callControlPlane<{ id: string }>(
    `/api/v1/chat/conversations/by-number/${conversationId}`,
  );
  if (!result.ok) redirect("/");
  redirect(`/chat/${result.data.id}`);
}
