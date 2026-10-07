"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  decideMemory,
  editMemory,
  undoMemoryCapture,
  type MemoryCapture,
} from "@/lib/memory-capture-api";

/** 그리는 줄이다. 거절한 기록은 그리지 않는다. */
export function shownCaptures(captures: MemoryCapture[]): MemoryCapture[] {
  return captures.filter((capture) => capture.status !== "REJECTED");
}

type Mode = "edit" | "edit-accept" | null;

type Actions = ReturnType<typeof useCaptureActions>;

/** 기록 한 줄의 고치기 상태와 단추 처리다. 처리를 마치면 `onChanged` 로 목록을 다시 읽는다. */
function useCaptureActions(
  capture: MemoryCapture,
  onChanged: (removedId?: number) => void,
) {
  const [mode, setMode] = useState<Mode>(null);
  const [draft, setDraft] = useState(capture.content);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(action: () => Promise<{ ok: boolean; message?: string }>) {
    setBusy(true);
    setError(null);
    const result = await action();
    setBusy(false);
    if (!result.ok) {
      setError(result.message ?? "처리하지 못했어요.");
      return false;
    }
    return true;
  }

  return {
    mode,
    draft,
    busy,
    error,
    setDraft,
    open(next: Exclude<Mode, null>) {
      setDraft(capture.content);
      setMode(next);
    },
    cancel() {
      setMode(null);
      setDraft(capture.content);
      setError(null);
    },
    async undo() {
      if (await run(() => undoMemoryCapture(capture.id))) onChanged(capture.id);
    },
    async reject() {
      if (await run(() => decideMemory(capture.memoryId, "reject")))
        onChanged(capture.id);
    },
    async accept() {
      if (await run(() => decideMemory(capture.memoryId, "accept")))
        onChanged();
    },
    async save() {
      const done = await run(async () => {
        const edited = await editMemory(capture.memoryId, {
          content: draft,
          alwaysInject: capture.alwaysInject,
        });
        if (!edited.ok || mode !== "edit-accept") return edited;
        return decideMemory(capture.memoryId, "accept");
      });
      if (done) {
        setMode(null);
        onChanged();
      }
    },
  };
}

/** 본문을 그 자리에서 고치는 칸이다. 고치는 중이 아니면 그리지 않는다. */
function CaptureEditor({ actions }: { actions: Actions }) {
  if (actions.mode === null) return null;
  return (
    <div className="mt-2 flex flex-col gap-2">
      <Textarea
        aria-label="기억 내용"
        value={actions.draft}
        onChange={(event) => actions.setDraft(event.target.value)}
      />
      <div className="flex gap-2">
        <Button
          size="xs"
          data-testid="memory-capture-save"
          disabled={actions.busy || actions.draft.trim() === ""}
          loading={actions.busy}
          loadingText="저장하는 중"
          onClick={() => void actions.save()}
        >
          {actions.mode === "edit-accept" ? "저장하고 받아들이기" : "저장"}
        </Button>
        <Button
          size="xs"
          variant="outline"
          disabled={actions.busy}
          onClick={actions.cancel}
        >
          취소
        </Button>
      </div>
    </div>
  );
}

function CaptureError({ actions }: { actions: Actions }) {
  return actions.error ? (
    <p role="alert" className="mt-1 text-xs text-destructive">
      {actions.error}
    </p>
  ) : null;
}

/** 받아들이기를 기다리는 제안 카드다. */
function ProposalCard({
  capture,
  actions,
}: {
  capture: MemoryCapture;
  actions: Actions;
}) {
  return (
    <div
      data-testid="memory-capture"
      data-kind={capture.kind}
      data-status={capture.status}
      className="rounded-md border border-border bg-muted p-3 text-sm"
    >
      <p className="text-xs text-muted-foreground">받아들이면 기억해요</p>
      <p className="mt-1 font-semibold">{capture.title}</p>
      {capture.sensitive ? (
        <p className="mt-1 text-xs text-muted-foreground">
          민감한 내용이라 여기서 보이지 않아요.
        </p>
      ) : (
        <p className="mt-1 whitespace-pre-wrap">{capture.content}</p>
      )}
      <CaptureEditor actions={actions} />
      <CaptureError actions={actions} />
      {actions.mode === null ? (
        <div className="mt-2 flex flex-wrap gap-2">
          <Button
            size="xs"
            data-testid="memory-capture-accept"
            disabled={actions.busy}
            onClick={() => void actions.accept()}
          >
            받아들이기
          </Button>
          {capture.sensitive ? null : (
            <Button
              size="xs"
              variant="outline"
              data-testid="memory-capture-edit-accept"
              disabled={actions.busy}
              onClick={() => actions.open("edit-accept")}
            >
              고쳐서 받아들이기
            </Button>
          )}
          <Button
            size="xs"
            variant="outline"
            data-testid="memory-capture-reject"
            disabled={actions.busy}
            onClick={() => void actions.reject()}
          >
            거절
          </Button>
        </div>
      ) : null}
    </div>
  );
}

/** 저장된 기록의 「기억했어요」 줄이다. 제안을 받아들인 줄은 되돌리기가 없다. */
function RememberedLine({
  capture,
  actions,
}: {
  capture: MemoryCapture;
  actions: Actions;
}) {
  return (
    <div
      data-testid="memory-capture"
      data-kind={capture.kind}
      data-status={capture.status}
      className="text-sm text-muted-foreground"
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="min-w-0 break-words">
          {capture.kind === "UPDATED" ? "기억을 고쳤어요" : "기억했어요"}:{" "}
          {capture.title}
        </span>
        {actions.mode === null ? (
          <>
            {capture.sensitive ? null : (
              <Button
                size="xs"
                variant="link"
                data-testid="memory-capture-edit"
                disabled={actions.busy}
                onClick={() => actions.open("edit")}
              >
                고치기
              </Button>
            )}
            {capture.kind === "PROPOSED" ? null : (
              <Button
                size="xs"
                variant="link"
                data-testid="memory-capture-undo"
                disabled={actions.busy}
                onClick={() => void actions.undo()}
              >
                되돌리기
              </Button>
            )}
          </>
        ) : null}
      </div>
      <CaptureEditor actions={actions} />
      <CaptureError actions={actions} />
    </div>
  );
}

/**
 * 기억 기록 한 줄이다. 모델이 쓴 글이라 제목과 본문을 평문으로만 그린다(ADR-009).
 *
 * <p>항목이 `PROPOSED` 면 제안 카드를, 그 밖이면 「기억했어요」 줄을 보인다.
 */
function MemoryCaptureRow({
  capture,
  onChanged,
}: {
  capture: MemoryCapture;
  onChanged(removedId?: number): void;
}) {
  const actions = useCaptureActions(capture, onChanged);
  return capture.status === "PROPOSED" ? (
    <ProposalCard capture={capture} actions={actions} />
  ) : (
    <RememberedLine capture={capture} actions={actions} />
  );
}

/** 한 답 아래에 그 답의 기억 기록을 모아 보인다. 보일 줄이 없으면 아무것도 그리지 않는다. */
export function MemoryCaptureList({
  captures,
  onChanged,
}: {
  captures: MemoryCapture[];
  onChanged(removedId?: number): void;
}) {
  const shown = shownCaptures(captures);
  if (shown.length === 0) return null;
  return (
    <li
      data-testid="memory-captures"
      aria-label="기억 기록"
      className="-mt-4 flex flex-col gap-2 pl-10"
    >
      {shown.map((capture) => (
        <MemoryCaptureRow
          key={capture.id}
          capture={capture}
          onChanged={onChanged}
        />
      ))}
    </li>
  );
}
