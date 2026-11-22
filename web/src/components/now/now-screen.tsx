import { EmptyState } from "@/components/ui/empty-state";
import { LoadFailure } from "@/components/ui/page-state";
import { callControlPlane } from "@/lib/control-plane";
import { allCardsEmpty, type AttentionView } from "@/lib/attention";
import { AddFollowUpButton } from "./add-follow-up-button";
import { NowCard } from "./now-card";

/**
 * 지금 화면의 「할 것」 탭 내용이다. 머리와 탭은 `/now` page 가 그린다. 카드와 항목은 서버 응답의 순서대로 그리고 다시 정렬하지 않는다. 순서 규칙은 서버 하나가 갖는다.
 * 다섯 카드가 모두 비어 빈 화면 하나를 그릴 때도 그 아래에 「할 일 더하기」 를 둔다.
 */
export async function NowScreen() {
  const result = await callControlPlane<AttentionView>("/api/v1/attention");
  if (!result.ok) {
    return <LoadFailure message={result.message} />;
  }

  const view = result.data;
  return allCardsEmpty(view.cards) ? (
    <div className="flex flex-col gap-4">
      <EmptyState
        title="지금 확인할 것이 없어요"
        description="실패한 일, 내가 확인할 일, 맡긴 일이 생기면 여기에 보여요."
      />
      <AddFollowUpButton />
    </div>
  ) : (
    <div className="grid gap-4 md:grid-cols-2">
      {view.cards.map((card) => (
        <NowCard key={card.key} card={card} readAt={view.readAt} />
      ))}
    </div>
  );
}
