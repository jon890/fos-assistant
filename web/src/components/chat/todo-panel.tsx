"use client";

import { useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { cn } from "cn";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import {
  focusWithoutTooltip,
  TooltipButton,
} from "@/components/ui/tooltip-button";
import { useMediaQuery } from "@/components/ui/use-media-query";

export type TodoTab = "todo" | "browser";

const TABS: [TodoTab, string][] = [
  ["todo", "할 일"],
  ["browser", "내 브라우저"],
];

/** 화면 글자 칸이나 크게 보기 중인 화면에서 난 Esc 는 원격 페이지나 크게 보기가 받는다. 시트를 닫지 않는다. */
function escapeBelongsToScreen(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return (
    target.matches('input[aria-label="화면에 글자 넣기"]') ||
    target.closest('section[aria-modal="true"]') !== null
  );
}

/**
 * 대화 위 옆 패널이다. 「할 일」 과 「내 브라우저」 두 탭을 두고 보이는 탭의 본문만 그린다.
 * 넓으면 대화 옆에 붙고 좁으면 전체 폭 시트로 덮는다. 동작은 `docs/features/user-browser/design.md` 의
 * 「PR3 할 일 탭」 이 갖는다.
 *
 * <p>「내 브라우저」 탭은 보일 넘기기가 없으면(`browserEnabled` 가 거짓) 누를 수 없다.
 */
export function TodoPanel({
  tab,
  browserEnabled,
  onTabChange,
  onClose,
  children,
}: {
  tab: TodoTab;
  browserEnabled: boolean;
  onTabChange(tab: TodoTab): void;
  onClose(): void;
  children: ReactNode;
}) {
  const wide = useMediaQuery("(min-width: 1024px)");
  const closeRef = useRef<HTMLButtonElement>(null);

  const header = (
    <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 pt-2">
      <div
        role="tablist"
        aria-label="할 일과 내 브라우저"
        className="flex min-w-0 flex-1 gap-1"
      >
        {TABS.map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            id={`todo-tab-${key}`}
            aria-selected={tab === key}
            aria-controls="todo-panel-body"
            disabled={key === "browser" && !browserEnabled}
            onClick={() => onTabChange(key)}
            className={cn(
              "-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors duration-fast ease-out outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
              "disabled:cursor-default disabled:opacity-50",
              tab === key
                ? "border-primary text-foreground"
                : "border-transparent text-muted-foreground hover:text-foreground",
            )}
          >
            {label}
          </button>
        ))}
      </div>
      <TooltipButton ref={closeRef} label="옆 패널 닫기" onClick={onClose}>
        <X aria-hidden="true" />
      </TooltipButton>
    </header>
  );
  const body = (
    <div
      id="todo-panel-body"
      role="tabpanel"
      aria-labelledby={`todo-tab-${tab}`}
      data-testid={`todo-tab-${tab}`}
      className="flex min-h-0 flex-1 flex-col"
    >
      {children}
    </div>
  );

  if (wide !== false) {
    return (
      <aside
        data-testid="todo-panel"
        role="complementary"
        aria-label="할 일과 내 브라우저"
        className={cn(
          "relative flex min-w-0 flex-col",
          "w-[min(48rem,50vw)] shrink-0",
          "border-l border-border bg-card",
        )}
      >
        {header}
        {body}
      </aside>
    );
  }

  return (
    <Sheet
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <SheetContent
        side="right"
        data-testid="todo-panel"
        showCloseButton={false}
        aria-describedby={undefined}
        onOpenAutoFocus={(event) => {
          // 결과물 패널과 같다. 첫 단추의 Tooltip 이 첫 Esc 를 가져가지 않게 닫기 단추에 풀이 없이 준다.
          event.preventDefault();
          focusWithoutTooltip(closeRef.current);
        }}
        onEscapeKeyDown={(event) => {
          if (escapeBelongsToScreen(event.target)) event.preventDefault();
        }}
        className="min-w-0 gap-0 border-border bg-card data-[side=right]:w-full data-[side=right]:sm:max-w-none"
      >
        <SheetTitle className="sr-only">할 일과 내 브라우저</SheetTitle>
        {header}
        {body}
      </SheetContent>
    </Sheet>
  );
}
