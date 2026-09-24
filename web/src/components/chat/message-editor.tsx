"use client";

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";

type Props = {
  initialValue: string;
  value: string;
  onChange(value: string): void;
  onSave(text: string): Promise<void> | void;
  onCancel(): void;
};

/** 마지막 질문의 새 판을 쓸 때만 여는 입력칸이다. */
export function MessageEditor({ initialValue, value, onChange, onSave, onCancel }: Props) {
  const [saving, setSaving] = useState(false);
  const composing = useRef(false);
  const cannotSave = saving || value.trim().length === 0 || value === initialValue;

  async function save() {
    if (cannotSave) return;
    setSaving(true);
    try {
      await onSave(value);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
      className="w-full max-w-xl rounded-xl border border-border bg-surface p-3"
    >
      <textarea
        autoFocus
        aria-label="질문 수정"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onCompositionStart={() => { composing.current = true; }}
        onCompositionEnd={() => { composing.current = false; }}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            onCancel();
          } else if (event.key === "Enter" && !event.shiftKey &&
              !composing.current && !event.nativeEvent.isComposing) {
            event.preventDefault();
            void save();
          }
        }}
        className="min-h-24 w-full resize-y bg-transparent text-sm leading-6 outline-none"
      />
      <div className="mt-2 flex justify-end gap-2">
        <Button variant="ghost" size="sm" onClick={onCancel}>취소</Button>
        <Button type="submit" size="sm" disabled={cannotSave}>보내기</Button>
      </div>
    </form>
  );
}
