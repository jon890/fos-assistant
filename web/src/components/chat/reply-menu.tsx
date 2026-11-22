"use client";

import { useEffect, useRef, useState } from "react";

/** 길게 누른 것으로 보는 시간(ms)이다 */
const LONG_PRESS_MS = 500;

/** 마우스 없는 터치 기기에서만 길게 누르기 메뉴를 쓴다(설계 chat-reference DECISION5) */
function touchOnly(): boolean {
  return window.matchMedia("(hover: none)").matches;
}

/**
 * 휴대폰에서 메시지나 그림을 길게 누르면 「답장」 과 「복사」 메뉴를 연다(스펙 chat-reference REQ3, DECISION23).
 *
 * <p>`bind` 를 누를 요소에 펼친다. 같은 요소의 브라우저 글 선택과 그림 저장 메뉴를 끈다. 「복사」 는 그 메시지의 글 전체를 복사한다.
 */
export function useReplyMenu({
  onReply,
  copyText,
}: {
  onReply?(): void;
  copyText: string;
}) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [at, setAt] = useState<{ x: number; y: number } | null>(null);
  // 길게 누른 손가락을 뗄 때 생기는 클릭이 바로 메뉴를 닫지 않게 연 시각을 둔다.
  const openedAt = useRef(0);
  const cancel = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  const bind = onReply
    ? {
        // 터치로 누른 것만 센다. 마우스는 단추 줄을 쓴다(설계 chat-reference DECISION5).
        onPointerDown: (event: React.PointerEvent) => {
          if (event.pointerType !== "touch") return;
          // 사진처럼 안쪽에서 먼저 받은 누르기를 바깥 말풍선이 다시 받지 않게 한다.
          event.stopPropagation();
          const point = { x: event.clientX, y: event.clientY };
          cancel();
          timer.current = setTimeout(() => {
            openedAt.current = Date.now();
            setAt(point);
          }, LONG_PRESS_MS);
        },
        onPointerUp: cancel,
        onPointerCancel: cancel,
        onPointerLeave: cancel,
        onContextMenu: (event: React.MouseEvent) => {
          if (touchOnly()) event.preventDefault();
        },
        className:
          "[@media(hover:none)]:select-none [@media(hover:none)]:[-webkit-touch-callout:none]",
      }
    : { className: "" };
  const close = () => setAt(null);
  const menuRef = useRef<HTMLDivElement>(null);
  // 열리면 첫 항목에 초점을 두고 Esc 로 닫는다.
  useEffect(() => {
    if (!at) return;
    menuRef.current?.querySelector<HTMLElement>("[role=menuitem]")?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setAt(null);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [at]);
  const closeOutside = () => {
    if (Date.now() - openedAt.current > 400) close();
  };
  const menu =
    at && onReply ? (
      <div
        className="fixed inset-0 z-50"
        data-testid="reply-menu-backdrop"
        onClick={closeOutside}
      >
        <div
          role="menu"
          ref={menuRef}
          data-testid="reply-menu"
          onClick={(event) => event.stopPropagation()}
          // 누른 자리가 이어지는 수라 클래스로 만들 수 없다. 화면 밖으로 나가지 않게 오른쪽과 아래를 줄인다
          style={{
            left: Math.min(at.x, window.innerWidth - 160),
            top: Math.min(at.y, window.innerHeight - 100),
          }}
          className="absolute flex w-36 flex-col rounded-md border border-border bg-popover p-1 text-sm shadow-md"
        >
          <button
            type="button"
            role="menuitem"
            className="rounded px-3 py-2 text-left hover:bg-muted"
            onClick={() => {
              close();
              onReply();
            }}
          >
            답장
          </button>
          <button
            type="button"
            role="menuitem"
            className="rounded px-3 py-2 text-left hover:bg-muted"
            onClick={() => {
              close();
              void navigator.clipboard?.writeText(copyText);
            }}
          >
            복사
          </button>
        </div>
      </div>
    ) : null;
  return { bind, menu };
}

/** 자식을 길게 누르면 메뉴를 여는 감싸개다. 목록 안에서 훅을 쓰지 못하는 자리(그림 목록)에 쓴다 */
export function ReplyPress({
  onReply,
  copyText,
  children,
}: {
  onReply?(): void;
  copyText: string;
  children: React.ReactNode;
}) {
  const menu = useReplyMenu({ onReply, copyText });
  return (
    <div {...menu.bind}>
      {children}
      {menu.menu}
    </div>
  );
}
