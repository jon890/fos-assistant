"use client";

import {
  useCallback,
  useEffect,
  useRef,
  type Dispatch,
  type SetStateAction,
} from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { HANDOFF_ENTRY_OPEN, TODO_ENTRY_PARAM } from "@/lib/notification";
import { activeAsk, todoLabel } from "@/lib/todo";
import { useApprovalList } from "./approval-list";
import { AskCard } from "./ask-card";
import type { SidePanelTarget } from "./conversation-session-types";
import type { Turn } from "./message-types";
import { TodoPanel, type TodoTab } from "./todo-panel";
import { useConversationHandoffs } from "./use-conversation-handoffs";

/**
 * 사람이 할 일(묻는 카드, 화면 넘기기, 승인)을 옆 패널의 「할 일」 탭에 모으고 입력창 위에 「할 일 N건」 한 줄을 둔다.
 * 동작은 `docs/features/user-browser/design.md` 의 「PR3 할 일 탭」 이 갖는다.
 *
 * <p>새 승인 줄이 보이거나 주소의 `todo=open`(승인 알림 진입)이 오면 「할 일」 탭을 연다. 「할 일」 탭을 보는 중에
 * 카드가 모두 사라지면 패널을 닫는다. 넘기기와 「내 브라우저」 탭은 `useConversationHandoffs` 가 갖는다.
 */
export function useConversationTodo({
  conversationId,
  refreshKey,
  panelTarget,
  setPanelTarget,
  submit,
  turns,
  selectedVersions,
  sending,
}: {
  conversationId: string | null;
  refreshKey: number;
  panelTarget: SidePanelTarget | null;
  setPanelTarget: Dispatch<SetStateAction<SidePanelTarget | null>>;
  submit: (text: string) => void;
  turns: Turn[];
  selectedVersions: Record<number, number>;
  sending: boolean;
}) {
  const searchParams = useSearchParams();
  const entry = searchParams.get(TODO_ENTRY_PARAM) === HANDOFF_ENTRY_OPEN;
  const router = useRouter();
  const pathname = usePathname();

  const openTodo = useCallback(
    () =>
      setPanelTarget((previous) => ({
        kind: "todo",
        tab: "todo",
        // 보던 넘기기는 기억해 「내 브라우저」 탭으로 돌아갈 수 있게 한다.
        handoff: previous?.kind === "todo" ? previous.handoff : null,
      })),
    [setPanelTarget],
  );
  const closePanel = useCallback(() => {
    setPanelTarget(null);
    // 닫기 단추가 사라지므로 초점을 입력창 위 「할 일」 줄로 돌려준다.
    window.requestAnimationFrame(() =>
      document
        .querySelector<HTMLElement>('[data-testid="todo-summary"]')
        ?.focus(),
    );
  }, [setPanelTarget]);

  const approvals = useApprovalList({
    conversationId,
    refreshKey,
    onFresh: openTodo,
  });
  const handoffs = useConversationHandoffs({
    conversationId,
    refreshKey,
    panelTarget,
    setPanelTarget,
    closePanel,
    submit,
  });
  const ask = activeAsk(turns, selectedVersions, sending);
  const count = approvals.count + handoffs.count + (ask ? 1 : 0);
  const hasContent =
    approvals.view !== null || handoffs.count > 0 || ask !== null;

  // 승인 알림에서 들어왔다. 승인 줄을 처음 성공해 읽은 뒤 보일 것이 있으면 「할 일」 탭을 연다.
  useEffect(() => {
    if (!entry || !approvals.ready) return;
    if (hasContent) openTodo();
    // 경로는 그대로라 대화 화면이 다시 만들어지지 않는다. 다른 검색어는 둔다.
    const rest = new URLSearchParams(searchParams.toString());
    rest.delete(TODO_ENTRY_PARAM);
    const query = rest.toString();
    router.replace(query ? `${pathname}?${query}` : pathname);
  }, [
    entry,
    approvals.ready,
    hasContent,
    openTodo,
    router,
    pathname,
    searchParams,
  ]);

  // 「할 일」 탭을 보는 중에 카드가 모두 사라졌다. 빈 패널이 대화를 덮지 않게 닫는다.
  const hadContent = useRef(hasContent);
  useEffect(() => {
    const had = hadContent.current;
    hadContent.current = hasContent;
    if (!had || hasContent) return;
    setPanelTarget((previous) =>
      previous?.kind === "todo" && previous.tab === "todo" ? null : previous,
    );
  }, [hasContent, setPanelTarget]);

  function changeTab(tab: TodoTab) {
    setPanelTarget((previous) => {
      if (previous?.kind !== "todo") return previous;
      if (tab === "browser")
        return previous.handoff
          ? { kind: "todo", tab: "browser", handoff: previous.handoff }
          : previous;
      return { kind: "todo", tab: "todo", handoff: previous.handoff };
    });
  }

  const todo = (
    <div className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
      {ask ? (
        // 답 안의 카드와 고른 것을 따로 든다. 새 물음이면 고른 것을 비운다.
        <AskCard
          key={JSON.stringify(ask)}
          ask={ask}
          active
          answered={null}
          onSubmit={submit}
        />
      ) : null}
      {handoffs.cards}
      {approvals.view}
      {hasContent ? null : (
        <p className="py-8 text-center text-sm text-muted-foreground">
          지금 할 일이 없어요
        </p>
      )}
    </div>
  );

  return {
    summary: hasContent ? (
      <div className="px-1">
        <div className="mx-auto mb-2 w-full max-w-3xl">
          <Button
            size="sm"
            variant="outline"
            data-testid="todo-summary"
            onClick={openTodo}
          >
            {todoLabel(count)}
          </Button>
        </div>
      </div>
    ) : null,
    panel:
      panelTarget?.kind === "todo" ? (
        <TodoPanel
          tab={panelTarget.tab}
          browserEnabled={panelTarget.handoff !== null}
          onTabChange={changeTab}
          onClose={closePanel}
        >
          {panelTarget.tab === "todo" ? todo : handoffs.browser}
        </TodoPanel>
      ) : null,
  };
}
