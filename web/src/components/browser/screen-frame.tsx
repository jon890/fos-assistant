"use client";

import type { ReactNode, RefObject } from "react";
import { cn } from "cn";
import type { Frame } from "./use-screen-stream";
import type { useScreenPointer } from "./use-screen-pointer";

/** 실제 화면 칸은 프레임 도착 여부와 관계없이 크기를 유지한다. */
export function ScreenFrame({
  areaRef,
  imgRef,
  expanded,
  frame,
  closed,
  pointer,
  children,
}: {
  areaRef: RefObject<HTMLDivElement | null>;
  imgRef: RefObject<HTMLImageElement | null>;
  expanded: boolean;
  frame: Frame | null;
  closed: boolean;
  pointer: ReturnType<typeof useScreenPointer>;
  children: ReactNode;
}) {
  return (
    <div
      ref={areaRef}
      className={cn(
        "relative h-[65dvh] min-h-40 min-w-0 flex-1 touch-none overflow-hidden rounded-md border border-border bg-muted select-none",
        expanded && "h-auto",
      )}
    >
      {children}
      {frame ? (
        // eslint-disable-next-line @next/next/no-img-element -- SSE 로 받은 data URL 이라 이미지 최적화를 거치지 않는다.
        <img
          ref={imgRef}
          src={`data:image/jpeg;base64,${frame.data}`}
          width={frame.width}
          height={frame.height}
          alt="내 브라우저 화면"
          draggable={false}
          className="block h-full w-full object-contain object-center"
          {...pointer}
        />
      ) : closed ? null : (
        <p className="p-4 text-sm text-muted-foreground">
          화면을 여는 중이에요…
        </p>
      )}
    </div>
  );
}
