"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type Dispatch,
  type SetStateAction,
} from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { HANDOFF_TEXT, newHandoffs, type Handoff } from "@/lib/handoff";
import { readHandoffs } from "@/lib/handoff-api";
import { HANDOFF_ENTRY_OPEN, HANDOFF_ENTRY_PARAM } from "@/lib/notification";
import type { SidePanelTarget } from "./conversation-session-types";
import { HandoffList } from "./handoff-list";
import { HandoffPanel } from "./handoff-panel";

/** 대화마다 마지막으로 읽은 목록이다. `ok` 는 읽기에 성공했는지다. 실패한 첫 읽기는 빈 목록으로 둔다. */
type Loaded = { conversationId: string; handoffs: Handoff[]; ok: boolean };

/** 대화마다 들고 있는 번호 묶음이다. 대화가 바뀌면 버린다. */
type Ids = { conversationId: string; ids: ReadonlySet<string> };

function idsFor(current: Ids | null, conversationId: string): Set<string> {
  return new Set(
    current?.conversationId === conversationId ? current.ids : undefined,
  );
}

/**
 * 대화의 화면 넘기기 카드와 「내 브라우저」 옆 패널을 잇는다. 동작은 `docs/features/user-browser.md` 의 「대화 위 화면」 이 갖는다.
 *
 * <p>카드 목록은 `refreshKey` 가 바뀌면 다시 읽는다. 넘기기를 적거나 닫을 때 오는 대화 사건이 올린다. 처음 성공한 읽기의
 * 번호를 기억해, 그 뒤에 처음 보인 넘기기에서만 패널을 저절로 연다. 주소의 `handoff=open`(알림 진입)은 가장 최근
 * 넘기기로 패널을 열고 주소에서 지운다.
 */
export function useConversationHandoffs({
  conversationId,
  refreshKey,
  panelTarget,
  setPanelTarget,
  submit,
}: {
  conversationId: string | null;
  refreshKey: number;
  panelTarget: SidePanelTarget | null;
  setPanelTarget: Dispatch<SetStateAction<SidePanelTarget | null>>;
  submit: (text: string) => void;
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  /** 「다 했어요」 를 누른 넘기기다. 서버가 닫기 전에 다시 누르지 않게 감춘다. */
  const [hidden, setHidden] = useState<Ids | null>(null);
  /** 처음 성공한 읽기부터 본 번호다. 여기에 없는 번호가 새 카드다. */
  const seen = useRef<Ids | null>(null);
  const hiddenRef = useRef(hidden);
  useEffect(() => {
    hiddenRef.current = hidden;
  }, [hidden]);
  const searchParams = useSearchParams();
  const entry = searchParams.get(HANDOFF_ENTRY_PARAM) === HANDOFF_ENTRY_OPEN;
  const router = useRouter();
  const pathname = usePathname();

  const open = useCallback(
    (handoff: Handoff) => setPanelTarget({ kind: "handoff", handoff }),
    [setPanelTarget],
  );

  useEffect(() => {
    if (conversationId === null) return;
    let stale = false;
    void readHandoffs(conversationId).then((result) => {
      if (stale) return;
      if (!result.ok) {
        setLoaded((current) =>
          current?.conversationId === conversationId
            ? current
            : { conversationId, handoffs: [], ok: false },
        );
        return;
      }
      const hiddenIds = idsFor(hiddenRef.current, conversationId);
      const shown = result.data.filter(
        (handoff) => !hiddenIds.has(handoff.handoffId),
      );
      const known =
        seen.current?.conversationId === conversationId ? seen.current : null;
      // 처음 성공한 읽기의 카드는 이미 있던 것이라 열지 않는다. 그 뒤에 처음 보인 것 가운데 가장 최근 것을 연다.
      const fresh = known ? newHandoffs(known.ids, shown)[0] : undefined;
      if (fresh) open(fresh);
      seen.current = {
        conversationId,
        ids: new Set([
          ...idsFor(known, conversationId),
          ...result.data.map((handoff) => handoff.handoffId),
        ]),
      };
      // 보이던 넘기기가 닫혔으면 패널도 닫는다. 다른 옆 패널은 건드리지 않는다.
      const openIds = new Set(shown.map((handoff) => handoff.handoffId));
      setPanelTarget((previous) =>
        previous?.kind === "handoff" && !openIds.has(previous.handoff.handoffId)
          ? null
          : previous,
      );
      setLoaded({ conversationId, handoffs: result.data, ok: true });
    });
    return () => {
      stale = true;
    };
  }, [conversationId, refreshKey, open, setPanelTarget]);

  const current =
    loaded !== null && loaded.conversationId === conversationId ? loaded : null;
  const hiddenIds = idsFor(hidden, conversationId ?? "");
  const shown = (current?.handoffs ?? []).filter(
    (handoff) => !hiddenIds.has(handoff.handoffId),
  );
  const ready = current?.ok === true;
  const latest = shown[0];

  // 알림에서 들어왔다. 같은 대화를 보는 중에 눌러도 검색어가 바뀌어 여기로 온다. 성공한 읽기가 있을 때 처리한다.
  useEffect(() => {
    if (!entry || !ready) return;
    if (latest) open(latest);
    // 경로는 그대로라 대화 화면이 다시 만들어지지 않고 입력창의 글과 첨부가 남는다. 다른 검색어는 둔다.
    const rest = new URLSearchParams(searchParams.toString());
    rest.delete(HANDOFF_ENTRY_PARAM);
    const query = rest.toString();
    router.replace(query ? `${pathname}?${query}` : pathname);
  }, [entry, ready, latest, open, router, pathname, searchParams]);

  const done = useCallback(
    (handoff: Handoff) => {
      if (conversationId === null) return;
      setHidden((previous) => ({
        conversationId,
        ids: new Set([...idsFor(previous, conversationId), handoff.handoffId]),
      }));
      // 사람이 보낸 turn 이 열린 넘기기를 모두 닫으므로 넘기기 패널은 무엇이든 닫는다.
      setPanelTarget((previous) =>
        previous?.kind === "handoff" ? null : previous,
      );
      submit(HANDOFF_TEXT[handoff.need].done);
    },
    [conversationId, setPanelTarget, submit],
  );

  const panelHandoff =
    panelTarget?.kind === "handoff" ? panelTarget.handoff : null;
  return {
    list: <HandoffList handoffs={shown} onOpen={open} onDone={done} />,
    panel: panelHandoff ? (
      <HandoffPanel
        key={panelHandoff.handoffId}
        handoff={panelHandoff}
        onClose={() => {
          setPanelTarget(null);
          // 닫기 단추가 사라지므로 초점을 그 카드의 「내 브라우저 열기」 로 돌려준다.
          window.requestAnimationFrame(() =>
            document
              .querySelector<HTMLElement>(
                `[data-handoff-open="${panelHandoff.handoffId}"]`,
              )
              ?.focus(),
          );
        }}
        onDone={() => done(panelHandoff)}
      />
    ) : null,
  };
}
