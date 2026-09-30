import Link from "next/link";
import { cn } from "cn";

export const USAGE_TABS = [
  { value: "summary", label: "요약" },
  { value: "executions", label: "실행 기록" },
  { value: "skills", label: "스킬" },
  { value: "fingerprints", label: "입력 지문" },
] as const;

export type UsageTab = (typeof USAGE_TABS)[number]["value"];

/** 주소의 `tab` 값을 탭으로 바꾼다. 없거나 모르는 값이면 요약이다. */
export function parseUsageTab(value: string | string[] | undefined): UsageTab {
  const asked = Array.isArray(value) ? value[0] : value;
  return USAGE_TABS.find((tab) => tab.value === asked)?.value ?? "summary";
}

/** 탭 줄이다. 단추마다 주소의 `?tab=` 만 바꾸는 링크이고, 고른 탭은 새로 고쳐도 남는다. */
export function UsageTabs({ current }: { current: UsageTab }) {
  return (
    <nav aria-label="사용량 탭" className="mb-6 flex overflow-x-auto border-b border-border">
      {USAGE_TABS.map((tab) => (
        <Link
          key={tab.value}
          href={tab.value === "summary" ? "/usage" : `/usage?tab=${tab.value}`}
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
