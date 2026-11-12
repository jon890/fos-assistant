"use client";

import { useRef, useState } from "react";
import { X } from "lucide-react";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import {
  focusWithoutTooltip,
  TooltipButton,
} from "@/components/ui/tooltip-button";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { BrowserScreen } from "@/components/browser/browser-screen";
import type { ScreenTab } from "@/components/browser/browser-screen-toolbar";
import {
  HANDOFF_TEXT,
  handoffBrowserHref,
  rebaseHandoffWatch,
  watchHandoffTab,
  type Handoff,
  type HandoffWatch,
} from "@/lib/handoff";

/** 패널은 브라우저 상태를 다시 읽을 것이 없다. 렌더마다 같은 함수여야 화면 연결이 다시 열리지 않는다. */
const NOOP = () => {};

/** 화면 글자 칸이나 크게 보기 중인 화면에서 난 Esc 는 원격 페이지나 크게 보기가 받는다. 시트를 닫지 않는다. */
function escapeBelongsToScreen(
  area: HTMLElement | null,
  target: EventTarget | null,
): boolean {
  if (!area || !(target instanceof Element) || !area.contains(target))
    return false;
  return (
    target.matches('input[aria-label="화면에 글자 넣기"]') ||
    target.closest('section[aria-modal="true"]') !== null
  );
}

/**
 * 화면 넘기기 카드의 탭을 대화 위 옆 패널에 띄운다. 넓으면 대화 옆에 붙고 좁으면 전체 폭 시트로 덮는다.
 * 동작은 `docs/features/user-browser.md` 의 「대화 위 화면」 이 갖는다.
 *
 * <p>넘긴 탭의 주소가 바뀌면 끝났는지 묻기만 하고 보내지 않는다. 판정 상태는 이 패널이 들고 있어, 다른 창에 밀린 뒤
 * 「다시 열기」 로 화면을 새로 만들어도 기준 주소가 남는다.
 */
export function HandoffPanel({
  handoff,
  onClose,
  onDone,
}: {
  handoff: Handoff;
  onClose(): void;
  onDone(): void;
}) {
  const wide = useMediaQuery("(min-width: 1024px)");
  const closeRef = useRef<HTMLButtonElement>(null);
  const areaRef = useRef<HTMLDivElement>(null);
  const watch = useRef<HandoffWatch>({ phase: "waiting" });
  const lastTabs = useRef<ScreenTab[]>([]);
  const [changed, setChanged] = useState(false);
  // 다시 열 때마다 화면을 새로 만들어 첫 탭 목록에서 넘긴 탭으로 다시 옮긴다.
  const [screenKey, setScreenKey] = useState(0);
  const text = HANDOFF_TEXT[handoff.need];

  function onTabs(tabs: ScreenTab[]) {
    lastTabs.current = tabs;
    const next = watchHandoffTab(watch.current, handoff.tab, tabs);
    watch.current = next.state;
    if (next.changed) setChanged(true);
  }

  function notYet() {
    watch.current = rebaseHandoffWatch(handoff.tab, lastTabs.current);
    setChanged(false);
  }

  const header = (title: React.ReactNode) => (
    <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-3">
      {title}
      <a
        href={handoffBrowserHref(handoff)}
        target="_blank"
        rel="noopener"
        data-testid="handoff-open-window"
        onClick={onClose}
        className="shrink-0 text-xs text-muted-foreground underline underline-offset-4"
      >
        새 창으로 열기
      </a>
      <TooltipButton ref={closeRef} label="내 브라우저 닫기" onClick={onClose}>
        <X aria-hidden="true" />
      </TooltipButton>
    </header>
  );
  const body = (
    <>
      <div ref={areaRef} className="min-h-0 flex-1 overflow-y-auto p-3">
        <BrowserScreen
          key={screenKey}
          startUrl={null}
          startTab={handoff.tab}
          onOpen={NOOP}
          onClose={onClose}
          onTabs={onTabs}
          onReopen={() => setScreenKey((value) => value + 1)}
        />
      </div>
      <footer className="space-y-2 border-t border-border px-4 py-3 text-sm">
        {changed ? (
          <div
            role="status"
            data-testid="handoff-changed"
            className="space-y-2 rounded-md border border-border bg-muted p-3"
          >
            <p>화면이 바뀌었어요. 다 마치셨나요?</p>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" onClick={onDone}>
                다 했어요, 이어서 해 주세요
              </Button>
              <Button size="sm" variant="outline" onClick={notYet}>
                아직이에요
              </Button>
            </div>
          </div>
        ) : null}
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-muted-foreground">
            마친 뒤 「다 했어요」를 누르면 이어서 해요.
          </p>
          <Button size="sm" onClick={onDone}>
            다 했어요
          </Button>
        </div>
      </footer>
    </>
  );
  const titleClass = "min-w-0 flex-1 truncate text-sm font-semibold";

  if (wide !== false) {
    return (
      <aside
        data-testid="handoff-panel"
        role="complementary"
        aria-label="내 브라우저"
        className={cn(
          "relative flex min-w-0 flex-col",
          "w-[min(48rem,50vw)] shrink-0",
          "border-l border-border bg-card",
        )}
      >
        {header(<h2 className={titleClass}>{text.title}</h2>)}
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
        data-testid="handoff-panel"
        showCloseButton={false}
        aria-describedby={undefined}
        onOpenAutoFocus={(event) => {
          // 결과물 패널과 같다. 첫 단추의 Tooltip 이 첫 Esc 를 가져가지 않게 닫기 단추에 풀이 없이 준다.
          event.preventDefault();
          focusWithoutTooltip(closeRef.current);
        }}
        onEscapeKeyDown={(event) => {
          if (escapeBelongsToScreen(areaRef.current, event.target))
            event.preventDefault();
        }}
        className="min-w-0 gap-0 border-border bg-card data-[side=right]:w-full data-[side=right]:sm:max-w-none"
      >
        {header(<SheetTitle className={titleClass}>{text.title}</SheetTitle>)}
        {body}
      </SheetContent>
    </Sheet>
  );
}
