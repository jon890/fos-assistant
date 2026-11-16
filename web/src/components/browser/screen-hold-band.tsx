"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { describeFailure } from "@/components/error-message";
import { giveBackBrowser, takeOverBrowser } from "@/lib/browser-api";
import type { HoldBand } from "@/lib/screen-hold";
import type { ScreenHold } from "./use-screen-stream";

/**
 * 띠의 「직접 할게요」, 「돌려주기」, 확인 띠의 상태다. 성공하면 다음 `hold` 사건을 기다리지 않고 띠를 바꾼다.
 * 확인 띠는 에이전트가 쓰는 중 띠일 때만 켠다.
 */
export function useScreenHold({
  hold,
  setHold,
  band,
  conversationId,
  onGivenBack,
  setNotice,
}: {
  hold: ScreenHold;
  setHold(hold: ScreenHold): void;
  band: HoldBand;
  conversationId: string | null;
  onGivenBack?: () => void;
  setNotice(message: string): void;
}) {
  const [asked, setAsked] = useState(false);
  const [busy, setBusy] = useState(false);

  async function call(
    request: () => Promise<Response>,
    next: ScreenHold,
    failed: string,
    after?: () => void,
  ) {
    setBusy(true);
    try {
      const response = await request();
      if (!response.ok) return setNotice(await describeFailure(response));
      setAsked(false);
      setHold(next);
      after?.();
    } catch {
      setNotice(failed);
    } finally {
      setBusy(false);
    }
  }

  return {
    band,
    confirm: asked && band === "agent",
    busy,
    human: hold.human,
    ask: () => setAsked(true),
    // 보기 전용 화면을 누르면 입력은 보내지 않고 「직접 하시겠어요?」 를 묻는다(spec DECISION6). 승인한 작업 중이면 묻지 않는다
    viewOnlyPointer:
      band === "agent" ? { onPointerDown: () => setAsked(true) } : {},
    onDismiss: () => setAsked(false),
    onTakeOver: () =>
      void call(
        () => takeOverBrowser(conversationId),
        { human: true, approved: false },
        "넘겨받지 못했어요. 잠시 뒤 다시 해 주세요.",
      ),
    onGiveBack: () =>
      void call(
        giveBackBrowser,
        { human: false, approved: false },
        "돌려주지 못했어요. 잠시 뒤 다시 해 주세요.",
        onGivenBack,
      ),
  };
}

/**
 * 화면 위 잠금 띠다. 에이전트가 쓰는 중이면 「직접 할게요」, 직접 하는 중이면 「돌려주기」, 승인한 작업 중이면 단추가 없다.
 * 보기 전용 화면을 누르거나 Esc 를 누르면 `confirm` 이 켜져 「직접 하시겠어요?」 를 묻는다. 동작은
 * `docs/features/browser-cowork/design.md` 의 「PR3 사람 잠금」 이 갖는다.
 */
export function ScreenHoldBand({
  band,
  confirm,
  busy,
  onTakeOver,
  onGiveBack,
  onDismiss,
}: {
  band: HoldBand;
  confirm: boolean;
  busy: boolean;
  onTakeOver(): void;
  onGiveBack(): void;
  onDismiss(): void;
}) {
  if (band === null) return null;
  if (band === "agent" && confirm)
    return (
      <div
        role="alertdialog"
        aria-label="직접 하시겠어요?"
        data-testid="takeover-confirm"
        className="flex flex-wrap items-center gap-2 rounded-md border border-primary bg-muted px-3 py-2 text-sm"
      >
        <span className="flex-1">
          직접 하시겠어요? 에이전트는 돌려줄 때까지 멈춰요.
        </span>
        <Button size="sm" disabled={busy} onClick={onTakeOver}>
          직접 할게요
        </Button>
        <Button size="sm" variant="outline" onClick={onDismiss}>
          계속 볼게요
        </Button>
      </div>
    );
  return (
    <div
      role="status"
      data-testid={band === "human" ? "human-band" : "agent-band"}
      className="flex flex-wrap items-center gap-2 rounded-md border border-border bg-muted px-3 py-2 text-sm"
    >
      <span className="flex-1">
        {band === "human"
          ? "직접 하는 중이에요."
          : band === "approved"
            ? "승인한 작업을 마무리하는 중이에요."
            : "에이전트가 쓰는 중이에요."}
      </span>
      {band === "human" ? (
        <Button size="sm" disabled={busy} onClick={onGiveBack}>
          돌려주기
        </Button>
      ) : band === "agent" ? (
        <Button size="sm" disabled={busy} onClick={onTakeOver}>
          직접 할게요
        </Button>
      ) : null}
    </div>
  );
}
