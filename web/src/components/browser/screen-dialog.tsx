"use client";

import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import type { ScreenInput } from "@/components/browser/screen-input";
import type { ScreenDialogView } from "@/components/browser/use-screen-stream";

const TITLES: Record<string, string> = {
  alert: "사이트가 알림 창을 띄웠어요",
  confirm: "사이트가 확인을 묻고 있어요",
  prompt: "사이트가 확인을 묻고 있어요",
  beforeunload: "이 페이지를 떠날지 묻고 있어요",
};

/**
 * 붙은 탭에 뜬 확인 창을 보이고 사람이 고른 답을 넘긴다. 창이 떠 있는 동안 그 탭은 다른 입력을 받지 않는다.
 * `prompt` 창은 글을 넣는 칸 없이 기본값으로 확인하거나 취소한다.
 */
export function ScreenDialog({
  dialog,
  send,
}: {
  dialog: ScreenDialogView;
  send: (input: ScreenInput) => void;
}) {
  const alertOnly = dialog.type === "alert";
  return (
    <Notice variant="info" role="alertdialog" aria-label="사이트 확인 창">
      <div className="flex flex-col gap-2">
        <strong>{TITLES[dialog.type] ?? TITLES.alert}</strong>
        {dialog.message ? (
          <p className="text-sm break-words whitespace-pre-wrap">
            {dialog.message}
          </p>
        ) : null}
        <div className="flex gap-2">
          <Button
            size="sm"
            onClick={() => send({ type: "dialog", action: "accept" })}
          >
            확인
          </Button>
          {alertOnly ? null : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => send({ type: "dialog", action: "dismiss" })}
            >
              취소
            </Button>
          )}
        </div>
      </div>
    </Notice>
  );
}
