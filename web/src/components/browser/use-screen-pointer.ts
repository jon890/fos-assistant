"use client";

import { useEffect, useRef, type PointerEvent, type RefObject } from "react";
import {
  containedBox,
  dragWheel,
  isTap,
  mouse,
  wheel,
  wheelDelta,
  type ScreenInput,
} from "@/components/browser/screen-input";

type Point = { clientX: number; clientY: number };
type Press = {
  id: number;
  touch: boolean;
  start: Point;
  last: Point;
  dragging: boolean;
};

/** 누른 채 움직임과 휠, 끌기를 보내는 최소 간격이다. 초당 20번까지다. */
const MOVE_MS = 50;

function point(event: Point): Point {
  return { clientX: event.clientX, clientY: event.clientY };
}

/** 디코딩된 그림의 비율로 여백을 뺀다. 크기 변경 전 프레임에도 같은 계산을 쓴다. */
function imageBox(img: HTMLImageElement) {
  return containedBox(
    img.getBoundingClientRect(),
    img.naturalWidth,
    img.naturalHeight,
  );
}

function inside(event: Point, box: ReturnType<typeof imageBox>) {
  return (
    event.clientX >= box.left &&
    event.clientX <= box.left + box.width &&
    event.clientY >= box.top &&
    event.clientY <= box.top + box.height
  );
}

/**
 * 그림 위의 포인터와 휠을 입력으로 바꾼다. 마우스는 누름, 뗌, 누른 채 움직임이고
 * 터치는 누르기와 세로 끌기(휠)다. 여러 손가락이면 첫 포인터만 따른다.
 */
export function useScreenPointer({
  areaRef,
  imgRef,
  keysRef,
  send,
  frameHeight,
}: {
  areaRef: RefObject<HTMLDivElement | null>;
  imgRef: RefObject<HTMLImageElement | null>;
  keysRef: RefObject<HTMLInputElement | null>;
  send: (input: ScreenInput) => void;
  frameHeight: number;
}) {
  const press = useRef<Press | null>(null);
  const lastMove = useRef(0);

  // 휠은 화면의 스크롤을 막아야 하므로 passive 가 아닌 리스너로 받는다.
  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;
    let pending = 0;
    let timer = 0;
    const onWheel = (event: WheelEvent) => {
      const img = imgRef.current;
      if (!img) return;
      event.preventDefault();
      const box = imageBox(img);
      if (!inside(event, box)) return;
      pending += wheelDelta(event.deltaY, event.deltaMode, box.height);
      if (timer) return;
      const at = point(event);
      timer = window.setTimeout(() => {
        timer = 0;
        send(wheel(at, imageBox(img), pending));
        pending = 0;
      }, MOVE_MS);
    };
    area.addEventListener("wheel", onWheel, { passive: false });
    return () => {
      window.clearTimeout(timer);
      area.removeEventListener("wheel", onWheel);
    };
  }, [areaRef, imgRef, send]);

  const box = () => imageBox(imgRef.current!);
  const scroll = (current: Press, to: Point) =>
    send(
      dragWheel(
        current.start,
        current.last.clientY,
        to.clientY,
        box(),
        frameHeight,
      ),
    );
  const pressOf = (event: PointerEvent) =>
    press.current?.id === event.pointerId ? press.current : null;

  function onPointerDown(event: PointerEvent<HTMLImageElement>) {
    if (press.current || !inside(event, box())) return;
    const touch = event.pointerType !== "mouse";
    if (!touch && event.button !== 0) return;
    event.preventDefault();
    if (!touch) keysRef.current?.focus({ preventScroll: true });
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // 이미 끝난 포인터면 잡지 못한다. 잡지 않아도 그림 안의 움직임은 받는다.
    }
    const start = point(event);
    press.current = {
      id: event.pointerId,
      touch,
      start,
      last: start,
      dragging: false,
    };
    if (!touch) send(mouse("down", start, box()));
  }

  function onPointerMove(event: PointerEvent<HTMLImageElement>) {
    const current = pressOf(event);
    if (!current) return;
    if (current.touch && !current.dragging && isTap(current.start, event))
      return;
    const now = performance.now();
    if (now - lastMove.current < MOVE_MS) return;
    lastMove.current = now;
    const to = point(event);
    if (current.touch) {
      current.dragging = true;
      scroll(current, to);
    } else send(mouse("move", to, box()));
    current.last = to;
  }

  function onPointerUp(event: PointerEvent<HTMLImageElement>) {
    const current = pressOf(event);
    if (!current) return;
    press.current = null;
    const to = point(event);
    if (!current.touch) return send(mouse("up", to, box()));
    if (!current.dragging && isTap(current.start, to)) {
      send(mouse("down", current.start, box()));
      return send(mouse("up", current.start, box()));
    }
    if (to.clientY !== current.last.clientY) scroll(current, to);
  }

  // 마우스가 취소되면 원격 화면이 누른 채로 남지 않게 마지막 자리에서 뗀다.
  function onPointerCancel(event: PointerEvent<HTMLImageElement>) {
    const current = pressOf(event);
    if (!current) return;
    press.current = null;
    if (!current.touch) send(mouse("up", current.last, box()));
  }

  return { onPointerDown, onPointerMove, onPointerUp, onPointerCancel };
}
