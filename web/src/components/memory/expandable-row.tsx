"use client";

import { ChevronDown } from "lucide-react";
import { useId } from "react";
import { cn } from "cn";

/**
 * 기억과 문서 목록의 한 줄이다. 제목과 짧은 표시만 보이고, 누르면 그 자리에서 펼쳐 자세한 내용을 보인다.
 *
 * <p>휴대폰과 데스크톱에서 같은 동작을 하도록 옆 패널이나 주소 이동을 쓰지 않는다.
 */
export function ExpandableRow({
  title,
  meta,
  open,
  onToggle,
  leaving = false,
  entering = false,
  tone = "plain",
  children,
}: {
  title: string;
  /** 제목 아래 한 줄에 보이는 짧은 표시들이다. */
  meta: React.ReactNode;
  open: boolean;
  onToggle(): void;
  leaving?: boolean;
  /** 화면을 연 뒤에 생긴 줄이다. 등장 움직임을 준다 */
  entering?: boolean;
  /** 검토할 줄은 바탕을 달리해 일반 줄과 구분한다. */
  tone?: "plain" | "review";
  children: React.ReactNode;
}) {
  const id = useId();
  const panelId = `${id}-panel`;
  return (
    <li
      data-leaving={leaving || undefined}
      data-open={open || undefined}
      className={cn(
        "list-none overflow-hidden rounded-md border border-border",
        tone === "review" && "bg-muted",
        entering && "animate-message-assistant",
      )}
    >
      <h3>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          // 줄 이름은 제목만이다. 짧은 표시는 설명으로 읽힌다.
          aria-labelledby={`${id}-title`}
          aria-describedby={`${id}-meta`}
          onClick={onToggle}
          className="flex w-full items-start gap-3 px-4 py-3 text-left transition-colors duration-fast ease-out outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-inset focus-visible:ring-ring/50"
        >
          <span className="min-w-0 flex-1">
            <span
              id={`${id}-title`}
              className="line-clamp-2 break-words font-semibold"
            >
              {title}
            </span>
            <span
              id={`${id}-meta`}
              className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground"
            >
              {meta}
            </span>
          </span>
          <ChevronDown
            aria-hidden
            className={cn(
              "mt-1 size-4 shrink-0 text-muted-foreground transition-transform duration-fast ease-out",
              open && "rotate-180",
            )}
          />
        </button>
      </h3>
      {open ? (
        <div id={panelId} className="border-t border-border px-4 py-3">
          {children}
        </div>
      ) : null}
    </li>
  );
}
