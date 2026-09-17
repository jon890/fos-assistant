"use client";

import { useLayoutEffect, useRef } from "react";

type Props = {
  value: string;
  disabled: boolean;
  onChange(value: string): void;
  onSend(): void;
};

export function Composer({ value, disabled, onChange, onSend }: Props) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const composing = useRef(false);

  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 120)}px`;
  }, [value]);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSend();
      }}
      className="flex items-end gap-2 border-t border-border pt-3"
    >
      <textarea
        ref={textareaRef}
        rows={1}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onCompositionStart={() => {
          composing.current = true;
        }}
        onCompositionEnd={() => {
          composing.current = false;
        }}
        onKeyDown={(event) => {
          if (event.key !== "Enter" || event.shiftKey || composing.current || event.nativeEvent.isComposing) {
            return;
          }
          event.preventDefault();
          onSend();
        }}
        disabled={disabled}
        placeholder="무엇을 도와줄까요"
        className="max-h-[7.5rem] min-h-10 flex-1 resize-none overflow-y-auto rounded-md border border-border bg-transparent px-3 py-2 text-base leading-6 disabled:opacity-50"
      />
      <button
        type="submit"
        disabled={disabled || value.trim().length === 0}
        className="h-10 rounded-md border border-border px-4 text-sm disabled:opacity-50"
      >
        보내기
      </button>
    </form>
  );
}
