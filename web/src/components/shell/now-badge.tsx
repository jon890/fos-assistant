import { cn } from "cn";
import type { NowIndicator } from "@/lib/attention";

/**
 * 「지금 볼 것」 줄과 단추에 붙는 수나 점이다. 낭독기에서는 숨기고 링크의 이름이 읽는다.
 *
 * <p>`floating` 은 아이콘 단추의 모서리에 띄운다. 아니면 글자 뒤에 잇는다.
 */
export function NowBadge({
  indicator,
  floating = false,
}: {
  indicator: NowIndicator | null;
  floating?: boolean;
}) {
  if (indicator === null) return null;
  if (indicator.kind === "dot") {
    return (
      <span
        aria-hidden="true"
        data-testid="now-dot"
        className={cn(
          "size-2 shrink-0 rounded-full bg-foreground",
          floating ? "absolute top-1 right-1" : "ml-2",
        )}
      />
    );
  }
  return (
    <span
      aria-hidden="true"
      data-testid="now-count"
      className={cn(
        "rounded-full text-xs",
        floating
          ? "absolute -top-1 -right-1 min-w-4 bg-foreground px-1 text-center leading-4 font-semibold text-background"
          : "ml-2 bg-muted px-1.5 py-0.5 text-foreground",
      )}
    >
      {indicator.text}
    </span>
  );
}
