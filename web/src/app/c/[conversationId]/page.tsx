import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { callControlPlane } from "@/lib/control-plane";

/**
 * 대화 번호로 된 옛 주소를 공개 식별자 주소로 넘겨 준다. 화면은 그리지 않는다.
 *
 * <p>Control Plane 이 요청자의 대화일 때만 식별자를 돌려준다. 없는 대화와 남의 대화는 둘 다 404 라 가리지 않고 `/` 로 보낸다.
 * 그 밖의 실패는 첫 화면으로 덮지 않고 throw 해서 Next 오류 화면으로 드러낸다.
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
  if (!result.ok) {
    if (result.status === 404) redirect("/");
    throw new Error(`옛 대화 주소를 넘겨 주지 못했다: ${result.status} ${result.code} ${result.message}`);
  }
  redirect(`/chat/${result.data.id}`);
}
