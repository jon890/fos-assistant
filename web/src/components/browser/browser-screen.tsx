"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { describeFailure } from "@/components/error-message";
import { openBrowserScreen, sendScreenInput } from "@/lib/browser-api";
import { readEventStream } from "@/lib/stream";
import {
  BrowserScreenToolbar,
  type ScreenTab,
} from "@/components/browser/browser-screen-toolbar";
import {
  dragWheel,
  isTap,
  mouse,
  resizeFor,
  screenKey,
  textInputs,
  wheel,
  type ScreenInput,
} from "@/components/browser/screen-input";

type Frame = { data: string; width: number; height: number };
type Point = { clientX: number; clientY: number };
type Press = { touch: boolean; start: Point; lastY: number; dragging: boolean };

/** 누른 채 움직임과 휠, 끌기를 보내는 최소 간격이다. 초당 20번까지다. */
const MOVE_MS = 50;
/** 사건을 받은 뒤 `closed` 없이 끊긴 SSE 를 다시 여는 간격이다. */
const RECONNECT_MS = 1_000;

const CLOSED: Record<string, string> = {
  replaced: "다른 창에서 화면을 열었어요.",
  stopped: "브라우저가 꺼졌어요.",
  timeout: "오래 쓰지 않아 화면을 닫았어요.",
};
const LOST = "화면 연결이 끊겼어요. 다시 열어 주세요.";

/**
 * 내 브라우저의 지금 탭을 그리고 누르기, 끌기, 글자를 보낸다. 동작은 `docs/backend/user-browser.md` 의 「로그인 화면」 이 갖는다.
 * 입력 내용은 로그에 남기지 않는다.
 */
export function BrowserScreen({
  startUrl,
  onOpen,
  onClose,
}: {
  startUrl: string | null;
  onOpen: () => void;
  onClose: () => void;
}) {
  const [attempt, setAttempt] = useState(0);
  const [frame, setFrame] = useState<Frame | null>(null);
  const [tabs, setTabs] = useState<ScreenTab[]>([]);
  const [closed, setClosed] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [address, setAddress] = useState(startUrl ?? "");
  const areaRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const keysRef = useRef<HTMLInputElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const open = useRef(false);
  const stream = useRef<AbortController | null>(null);
  const queue = useRef<Promise<void>>(Promise.resolve());
  const frameHeight = useRef(0);
  const press = useRef<Press | null>(null);
  const lastMove = useRef(0);
  const sentWidth = useRef(0);

  const close = useCallback((message: string) => {
    open.current = false;
    stream.current?.abort();
    setClosed(message);
  }, []);

  // 누름과 뗌의 순서가 바뀌지 않게 입력은 한 줄로 보낸다.
  const send = useCallback(
    (input: ScreenInput) => {
      if (!open.current) return;
      queue.current = queue.current.then(async () => {
        if (!open.current) return;
        try {
          const response = await sendScreenInput(input);
          if (response.status === 409) close(await describeFailure(response));
          else if (!response.ok) setNotice(await describeFailure(response));
        } catch {
          setNotice("입력을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
        }
      });
    },
    [close],
  );

  const resize = useCallback(() => {
    const width = areaRef.current?.clientWidth ?? 0;
    if (width === 0 || width === sentWidth.current) return;
    sentWidth.current = width;
    send(resizeFor(width));
  }, [send]);

  useEffect(() => {
    const controller = new AbortController();
    stream.current = controller;
    const run = async () => {
      let url = attempt === 0 ? startUrl : null;
      while (!controller.signal.aborted) {
        let reason: string | null = null;
        let received = false;
        try {
          const response = await openBrowserScreen(url, controller.signal);
          if (!response.ok) return close(await describeFailure(response));
          open.current = true;
          url = null;
          onOpen();
          sentWidth.current = 0;
          resize();
          await readEventStream<unknown>(response, (data, name) => {
            received = true;
            if (name === "frame") {
              frameHeight.current = (data as Frame).height;
              setFrame(data as Frame);
            } else if (name === "tabs") {
              const next = data as ScreenTab[];
              setTabs(next);
              const active = next.find((tab) => tab.active);
              if (active && document.activeElement !== addressRef.current)
                setAddress(active.url);
            } else if (name === "closed") {
              reason = (data as { reason: string }).reason;
            }
          });
        } catch {
          // 연결이 깨졌다. 끊은 것이 이 화면이 아니면 아래에서 판단한다.
        }
        if (controller.signal.aborted) return;
        if (reason !== null || !received) {
          return close(reason === null ? LOST : (CLOSED[reason] ?? LOST));
        }
        // 사건을 받던 연결이 말없이 끊겼다. 화면을 둔 채 다시 연다.
        await new Promise((resolve) => setTimeout(resolve, RECONNECT_MS));
      }
    };
    void run();
    return () => {
      open.current = false;
      controller.abort();
    };
  }, [attempt, startUrl, onOpen, close, resize]);

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;
    let timer = 0;
    const observer = new ResizeObserver(() => {
      window.clearTimeout(timer);
      timer = window.setTimeout(resize, 300);
    });
    observer.observe(area);
    // 휠은 화면의 스크롤을 막아야 하므로 passive 가 아닌 리스너로 받는다.
    let pending = 0;
    let wheelTimer = 0;
    const onWheel = (event: WheelEvent) => {
      const img = imgRef.current;
      if (!img) return;
      event.preventDefault();
      pending += event.deltaY;
      if (wheelTimer) return;
      const point = { clientX: event.clientX, clientY: event.clientY };
      wheelTimer = window.setTimeout(() => {
        wheelTimer = 0;
        send(wheel(point, img.getBoundingClientRect(), pending));
        pending = 0;
      }, MOVE_MS);
    };
    area.addEventListener("wheel", onWheel, { passive: false });
    // 휴대폰의 Backspace 는 keydown 에 키 이름이 오지 않아 beforeinput 으로 잡는다.
    const keys = keysRef.current;
    const onBeforeInput = (event: InputEvent) => {
      if (event.inputType !== "deleteContentBackward" || event.isComposing)
        return;
      if (keys?.value) return;
      event.preventDefault();
      send({ type: "key", key: "Backspace" });
    };
    keys?.addEventListener("beforeinput", onBeforeInput);
    return () => {
      observer.disconnect();
      window.clearTimeout(timer);
      window.clearTimeout(wheelTimer);
      area.removeEventListener("wheel", onWheel);
      keys?.removeEventListener("beforeinput", onBeforeInput);
    };
  }, [resize, send]);

  function box() {
    return imgRef.current!.getBoundingClientRect();
  }

  function pointerDown(event: React.PointerEvent<HTMLImageElement>) {
    const touch = event.pointerType !== "mouse";
    if (!touch && event.button !== 0) return;
    event.preventDefault();
    if (!touch) keysRef.current?.focus({ preventScroll: true });
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // 이미 끝난 포인터면 잡지 못한다. 잡지 않아도 그림 안의 움직임은 받는다.
    }
    const start = { clientX: event.clientX, clientY: event.clientY };
    press.current = { touch, start, lastY: event.clientY, dragging: false };
    if (!touch) send(mouse("down", start, box()));
  }

  function pointerMove(event: React.PointerEvent<HTMLImageElement>) {
    const current = press.current;
    if (!current) return;
    if (current.touch && !current.dragging && isTap(current.start, event))
      return;
    const now = performance.now();
    if (now - lastMove.current < MOVE_MS) return;
    lastMove.current = now;
    if (!current.touch) return send(mouse("move", event, box()));
    current.dragging = true;
    send(
      dragWheel(
        current.start,
        current.lastY,
        event.clientY,
        box(),
        frameHeight.current,
      ),
    );
    current.lastY = event.clientY;
  }

  function pointerUp(event: React.PointerEvent<HTMLImageElement>) {
    const current = press.current;
    press.current = null;
    if (!current) return;
    if (!current.touch) return send(mouse("up", event, box()));
    if (!current.dragging && isTap(current.start, event)) {
      send(mouse("down", current.start, box()));
      return send(mouse("up", current.start, box()));
    }
    if (event.clientY !== current.lastY) {
      send(
        dragWheel(
          current.start,
          current.lastY,
          event.clientY,
          box(),
          frameHeight.current,
        ),
      );
    }
  }

  // 조합을 마친 글자만 보내고 입력칸을 비운다. 조합 중인 한글은 compositionend 에서 보낸다.
  function flushText() {
    const keys = keysRef.current;
    if (!keys?.value) return;
    textInputs(keys.value).forEach(send);
    keys.value = "";
  }

  function reopen() {
    setClosed(null);
    setNotice(null);
    setFrame(null);
    setTabs([]);
    setAttempt((value) => value + 1);
  }

  return (
    <section aria-label="로그인 화면" className="space-y-3">
      {closed ? (
        <Notice variant="info" role="status">
          <span>{closed}</span>
          <Button size="sm" className="ml-2" onClick={reopen}>
            다시 열기
          </Button>
        </Notice>
      ) : null}
      <BrowserScreenToolbar
        address={address}
        onAddress={setAddress}
        addressRef={addressRef}
        tabs={tabs}
        disabled={closed !== null}
        send={send}
        onNotice={setNotice}
        onKeyboard={() => keysRef.current?.focus()}
        onClose={onClose}
      />
      {notice ? (
        <Notice variant="error" role="alert">
          {notice}
        </Notice>
      ) : null}
      <div
        ref={areaRef}
        className="relative min-h-40 touch-none overflow-hidden rounded-md border border-border bg-muted select-none"
      >
        <input
          ref={keysRef}
          aria-label="화면에 글자 넣기"
          autoCapitalize="off"
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          className="absolute top-0 left-0 size-px text-base opacity-0"
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return;
            // Shift+Tab 은 화면 밖으로 초점을 옮기게 둔다.
            if (event.key === "Tab" && event.shiftKey) return;
            const input = screenKey(event.key);
            if (!input) return;
            event.preventDefault();
            send(input);
          }}
          onInput={(event) => {
            if (!(event.nativeEvent as InputEvent).isComposing) flushText();
          }}
          onCompositionEnd={flushText}
        />
        {frame ? (
          // eslint-disable-next-line @next/next/no-img-element -- 프레임은 SSE 로 받은 data URL 이라 이미지 최적화를 거치지 않는다.
          <img
            ref={imgRef}
            src={`data:image/jpeg;base64,${frame.data}`}
            width={frame.width}
            height={frame.height}
            alt="내 브라우저 화면"
            draggable={false}
            className="block h-auto w-full"
            onPointerDown={pointerDown}
            onPointerMove={pointerMove}
            onPointerUp={pointerUp}
            onPointerCancel={() => {
              press.current = null;
            }}
          />
        ) : closed ? null : (
          <p className="p-4 text-sm text-muted-foreground">
            화면을 여는 중이에요…
          </p>
        )}
      </div>
    </section>
  );
}
