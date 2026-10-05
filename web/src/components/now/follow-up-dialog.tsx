"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { isoToSeoulInput, seoulInputToIso } from "@/lib/attention";
import {
  createFollowUp,
  updateFollowUp,
  type FollowUpView,
} from "@/lib/follow-up-api";

/** `backend` 의 제목 칸 길이(`follow_up.title`)와 같다. */
const MAX_TITLE_CHARS = 200;

/** 고칠 할 일이다. 제목은 항목의 `title`, 나머지는 항목의 `followUp` 칸에서 읽는다. */
export type EditableFollowUp = {
  id: string;
  title: string;
  dueAt: string | null;
  waiting: boolean;
};

/**
 * 할 일을 더하거나 고치는 대화 상자다. `followUp` 이 없으면 새로 더한다.
 *
 * <p>기한은 `Asia/Seoul` 의 날짜와 시각으로 받는다. 고치기에서 기한 칸을 비우면 `dueAt: null` 을 보내 기한을 지운다.
 * 저장하는 동안은 창이 닫히지 않는다.
 */
export function FollowUpDialog({
  open,
  onOpenChange,
  followUp,
  onSaved,
}: {
  open: boolean;
  onOpenChange(next: boolean): void;
  followUp: EditableFollowUp | null;
  onSaved(saved: FollowUpView): void;
}) {
  const [saving, setSaving] = useState(false);

  function changeOpen(next: boolean) {
    if (saving) return;
    onOpenChange(next);
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogContent showCloseButton={!saving}>
        {/* 닫히면 내용이 사라지므로 열 때마다 폼의 값을 `followUp` 에서 새로 채운다. */}
        <FollowUpForm
          followUp={followUp}
          saving={saving}
          onSavingChange={setSaving}
          onCancel={() => changeOpen(false)}
          onSaved={onSaved}
        />
      </DialogContent>
    </Dialog>
  );
}

function FollowUpForm({
  followUp,
  saving,
  onSavingChange,
  onCancel,
  onSaved,
}: {
  followUp: EditableFollowUp | null;
  saving: boolean;
  onSavingChange(next: boolean): void;
  onCancel(): void;
  onSaved(saved: FollowUpView): void;
}) {
  const id = useId();
  const [title, setTitle] = useState(followUp?.title ?? "");
  const [due, setDue] = useState(isoToSeoulInput(followUp?.dueAt ?? null));
  const [waiting, setWaiting] = useState(followUp?.waiting ?? false);
  const [error, setError] = useState<string | null>(null);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const dueAt = seoulInputToIso(due);
    if (due !== "" && dueAt === null) {
      setError("기한을 날짜와 시각으로 넣어 주세요.");
      return;
    }
    onSavingChange(true);
    setError(null);
    const input = { title: title.trim(), dueAt, waiting };
    const result = followUp
      ? await updateFollowUp(followUp.id, input)
      : await createFollowUp(input);
    onSavingChange(false);
    if (result.ok) onSaved(result.data);
    else setError(result.message);
  }

  return (
    <form onSubmit={(event) => void save(event)} className="grid gap-4">
      <DialogHeader>
        <DialogTitle>{followUp ? "할 일 고치기" : "할 일 더하기"}</DialogTitle>
        <DialogDescription>
          기한과 기다리는 중은 넣지 않아도 돼요.
        </DialogDescription>
      </DialogHeader>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-title`}>제목</Label>
        <Input
          id={`${id}-title`}
          value={title}
          maxLength={MAX_TITLE_CHARS}
          required
          disabled={saving}
          onChange={(event) => setTitle(event.target.value)}
        />
      </div>
      <div className="grid gap-1.5">
        <Label htmlFor={`${id}-due`}>기한</Label>
        <Input
          id={`${id}-due`}
          type="datetime-local"
          value={due}
          disabled={saving}
          onChange={(event) => setDue(event.target.value)}
        />
      </div>
      <div className="flex items-center justify-between gap-3">
        <Label htmlFor={`${id}-waiting`}>기다리는 중</Label>
        <Switch
          id={`${id}-waiting`}
          checked={waiting}
          disabled={saving}
          onCheckedChange={setWaiting}
        />
      </div>
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      <DialogFooter>
        <Button
          type="button"
          variant="outline"
          disabled={saving}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          type="submit"
          loading={saving}
          loadingText="저장하는 중…"
          disabled={title.trim() === ""}
        >
          저장
        </Button>
      </DialogFooter>
    </form>
  );
}
