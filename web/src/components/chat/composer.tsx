"use client";

import { useLayoutEffect, useRef } from "react";
import { Button } from "@/components/ui/button";

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
      className="mx-auto w-full max-w-3xl pt-3"
    >
      <div
        data-testid="composer-shell"
        className="flex items-end gap-2 rounded-3xl border border-border bg-background p-1.5 pl-4 focus-within:border-brand"
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
            if (
              event.key !== "Enter"
              || event.shiftKey
              || composing.current
              || event.nativeEvent.isComposing
            ) {
              return;
            }
            event.preventDefault();
            onSend();
          }}
          disabled={disabled}
          placeholder="무엇을 도와줄까요"
          className="max-h-[7.5rem] min-h-10 flex-1 resize-none overflow-y-auto bg-transparent py-2 text-base leading-6 outline-none disabled:opacity-50"
        />
        <Button
          type="submit"
          aria-label="보내기"
          disabled={disabled || value.trim().length === 0}
          className="h-10 w-10 shrink-0 rounded-full !p-0"
        >
          <svg
            viewBox="0 0 24 24"
            aria-hidden="true"
            className="h-5 w-5 fill-none stroke-current stroke-2"
          >
            <path
              d="M12 19V5m0 0-6 6m6-6 6 6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </Button>
      </div>
    </form>
  );
}
