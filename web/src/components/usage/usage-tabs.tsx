import Link from "next/link";
import { cn } from "cn";

export const USAGE_TABS = [
  { value: "summary", label: "요약" },
  { value: "executions", label: "실행 기록" },
  { value: "skills", label: "스킬" },
  { value: "fingerprints", label: "설정별 사용량" },
] as const;

export type UsageTab = (typeof USAGE_TABS)[number]["value"];

/** 설정별 사용량은 금액과 설정 구분값을 비교하는 탭이라 관리자에게만 보인다. */
function visibleTabs(isAdmin: boolean) {
  return USAGE_TABS.filter((tab) => isAdmin || tab.value !== "fingerprints");
}

/** 주소의 `tab` 값을 탭으로 바꾼다. 없거나 모르는 값이거나 그 역할에 보이지 않는 탭이면 요약이다. */
export function parseUsageTab(
  value: string | string[] | undefined,
  isAdmin: boolean,
): UsageTab {
  const asked = Array.isArray(value) ? value[0] : value;
  return (
    visibleTabs(isAdmin).find((tab) => tab.value === asked)?.value ?? "summary"
  );
}

/** 탭 줄이다. 단추마다 주소의 `?tab=` 만 바꾸는 링크이고, 고른 탭은 새로 고쳐도 남는다. */
export function UsageTabs({
  current,
  isAdmin,
  basePath,
}: {
  current: UsageTab;
  isAdmin: boolean;
  /** 탭 링크의 바탕 경로다. 일반 화면은 `/usage`, 관리자 영역은 `/admin/usage` 다. */
  basePath: string;
}) {
  return (
    <nav
      aria-label="사용량 탭"
      className="mb-6 flex overflow-x-auto border-b border-border"
    >
      {visibleTabs(isAdmin).map((tab) => (
        <Link
          key={tab.value}
          // 탭마다 집계를 다시 읽는다. 미리 읽기와 이동이 겹치지 않게 고른 탭만 읽는다.
          prefetch={false}
          href={
            tab.value === "summary" ? basePath : `${basePath}?tab=${tab.value}`
          }
          aria-current={tab.value === current ? "page" : undefined}
          className={cn(
            "-mb-px shrink-0 border-b-2 px-3 py-2 text-sm whitespace-nowrap hover:text-foreground",
            tab.value === current
              ? "border-foreground font-medium text-foreground"
              : "border-transparent text-muted-foreground",
          )}
        >
          {tab.label}
        </Link>
      ))}
    </nav>
  );
}
