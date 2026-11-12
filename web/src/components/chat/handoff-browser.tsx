"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
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

/**
 * 옆 패널의 「내 브라우저」 탭이다. 화면 넘기기 카드의 탭을 띄운다. 패널 껍데기와 탭 머리는 `TodoPanel` 이 갖는다.
 * 동작은 `docs/features/user-browser.md` 의 「대화 위 화면」 이 갖는다.
 *
 * <p>넘긴 탭의 주소가 바뀌면 끝났는지 묻기만 하고 보내지 않는다. 판정 상태는 이 패널이 들고 있어, 다른 창에 밀린 뒤
 * 「다시 열기」 로 화면을 새로 만들어도 기준 주소가 남는다.
 */
export function HandoffBrowser({
  handoff,
  onClose,
  onDone,
}: {
  handoff: Handoff;
  onClose(): void;
  onDone(): void;
}) {
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

  return (
    <div data-testid="handoff-panel" className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-semibold">
          {text.title}
        </h3>
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
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-3">
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
    </div>
  );
}
