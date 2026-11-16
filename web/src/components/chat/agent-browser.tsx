"use client";

import { useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { Monitor } from "lucide-react";
import { Button } from "@/components/ui/button";
import { BrowserScreen } from "@/components/browser/browser-screen";
import { readConnectors } from "@/lib/connection";
import {
  agentUsesBrowser,
  browserCatalog,
  needsBrowserCatalog,
  type BrowserCatalog,
} from "@/lib/agent-browser";
import type { ActivityState } from "./activity/activity-state";
import type { SidePanelTarget } from "./conversation-session-types";

/** 패널은 브라우저 상태를 다시 읽을 것이 없다. 렌더마다 같은 함수여야 화면 연결이 다시 열리지 않는다. */
const NOOP = () => {};

/**
 * 옆 패널의 「내 브라우저」 탭이 에이전트가 쥔 탭을 보이는 화면이다. 처음 연 화면을 에이전트가 쥔 첫 탭으로 옮긴다. 동작은
 * `docs/features/user-browser.md` 의 「대화 위 화면」 이 갖는다.
 */
export function AgentBrowser({
  nonce,
  onClose,
  conversationId,
  onGivenBack,
}: {
  nonce: number;
  onClose(): void;
  conversationId: string | null;
  /** 화면의 「돌려주기」 가 성공한 뒤 부른다. 그 대화에 열린 넘기기가 있으면 글을 보낸다. */
  onGivenBack(): void;
}) {
  // 다시 열 때마다 화면을 새로 만들어 에이전트 탭으로 다시 옮긴다.
  const [screenKey, setScreenKey] = useState(0);
  return (
    <div
      data-testid="agent-browser-panel"
      className="flex min-h-0 flex-1 flex-col"
    >
      <div className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-semibold">
          에이전트 화면
        </h3>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-3">
        <BrowserScreen
          key={`${nonce}:${screenKey}`}
          startUrl={null}
          followAgent
          conversationId={conversationId}
          onGivenBack={onGivenBack}
          onOpen={NOOP}
          onClose={onClose}
          onReopen={() => setScreenKey((value) => value + 1)}
        />
      </div>
    </div>
  );
}

/**
 * 이 대화에서 사용자 브라우저를 쓰는 플러그인 도구나 승인한 실행이 도는 동안 「에이전트 화면 보기」 단추를 만든다.
 * 판정은 `@/lib/agent-browser` 가, 카탈로그는 판정에 필요할 때 한 번만 읽는다. 읽지 못하면 다음에 다시 읽는다.
 */
export function useAgentBrowserButton({
  activity,
  executing,
  setPanelTarget,
}: {
  activity: ActivityState | null;
  executing: string[];
  setPanelTarget: Dispatch<SetStateAction<SidePanelTarget | null>>;
}) {
  const [catalog, setCatalog] = useState<BrowserCatalog | null>(null);
  const running = (activity?.items ?? []).flatMap((item) =>
    item.kind === "tool" && item.state === "running" ? [item.name] : [],
  );
  const wanted = catalog === null && needsBrowserCatalog(running, executing);

  useEffect(() => {
    if (!wanted) return;
    let stale = false;
    void readConnectors().then((result) => {
      if (!stale && result.ok) setCatalog(browserCatalog(result.data));
    });
    return () => {
      stale = true;
    };
  }, [wanted]);

  if (!agentUsesBrowser(running, executing, catalog)) return null;
  return (
    <Button
      size="sm"
      variant="outline"
      data-testid="agent-browser-open"
      onClick={() =>
        setPanelTarget({ kind: "agent-browser", nonce: Date.now() })
      }
    >
      <Monitor aria-hidden />
      에이전트 화면 보기
    </Button>
  );
}
