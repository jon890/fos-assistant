"use client";

import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import type { ScreenInput } from "./screen-input";

/** 위아래 단추는 한 화면씩 보내고 길게 누르면 반복한다. */
export function ScreenScrollControls({
  send,
  height,
  disabled,
}: {
  send: (input: ScreenInput) => void;
  height: number;
  disabled: boolean;
}) {
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const scroll = (direction: number) =>
    send({
      type: "wheel",
      x: 0.5,
      y: 0.5,
      deltaY: direction * Math.min(2000, Math.max(320, height)),
    });
  const stop = () => {
    if (timer.current) clearInterval(timer.current);
    timer.current = null;
  };
  useEffect(() => {
    window.addEventListener("blur", stop);
    return () => {
      stop();
      window.removeEventListener("blur", stop);
    };
  }, []);
  useEffect(() => {
    if (disabled) stop();
  }, [disabled]);

  return (
    <div
      aria-label="화면 스크롤"
      className="flex shrink-0 flex-wrap gap-2 md:flex-col"
    >
      {(
        [
          [-1, "위로"],
          [1, "아래로"],
        ] as const
      ).map(([direction, label]) => (
        <Button
          key={label}
          size="sm"
          variant="outline"
          disabled={disabled}
          className="touch-none"
          onPointerDown={(event) => {
            if (event.button !== 0) return;
            stop();
            event.currentTarget.setPointerCapture(event.pointerId);
            scroll(direction);
            timer.current = setInterval(() => scroll(direction), 350);
          }}
          onPointerUp={stop}
          onPointerCancel={stop}
          onLostPointerCapture={stop}
          onClick={(event) => {
            if (event.detail === 0) scroll(direction);
          }}
        >
          {label}
        </Button>
      ))}
      <Button
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={() => send({ type: "scroll", action: "top" })}
      >
        처음으로
      </Button>
      <Button
        size="sm"
        variant="outline"
        disabled={disabled}
        onClick={() => send({ type: "scroll", action: "bottom" })}
      >
        끝으로
      </Button>
    </div>
  );
}
