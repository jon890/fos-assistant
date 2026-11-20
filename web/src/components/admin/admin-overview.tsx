import { OverviewFailuresCard } from "./overview-failures-card";
import { OverviewMonthlyCard } from "./overview-monthly-card";

/**
 * 관리자 영역의 첫 화면이다. 그룹 전체의 실패와 이번 달 사용량을 카드 둘로 보인다.
 *
 * <p>카드는 따로 읽어 한쪽이 실패해도 다른 쪽은 그대로 보인다. 요구와 결정은 `docs/features/admin-area/spec.md` 가 갖는다.
 */
export function AdminOverview() {
  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="text-xl font-semibold">요약</h1>
      <p className="mt-1 mb-4 text-sm text-muted-foreground">
        그룹 전체의 실패와 이번 달 사용량을 한눈에 봐요
      </p>
      <div className="grid gap-4 md:grid-cols-2">
        <OverviewFailuresCard />
        <OverviewMonthlyCard />
      </div>
    </div>
  );
}
