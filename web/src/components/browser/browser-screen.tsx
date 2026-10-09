"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { BrowserScreenToolbar } from "@/components/browser/browser-screen-toolbar";
import {
  screenKey,
  textInputs,
  type ScreenInput,
} from "@/components/browser/screen-input";
import { useScreenPointer } from "@/components/browser/use-screen-pointer";
import { useScreenStream } from "@/components/browser/use-screen-stream";
import { useScreenFullscreen } from "./use-screen-fullscreen";
import { ScreenScrollControls } from "./screen-scroll-controls";
import { cn } from "cn";
import { ScreenFrame } from "./screen-frame";

/** 조합 중 Enter 와 조합이 끝난 뒤의 Enter 가 같은 누름이라고 보는 간격이다. */
const ENTER_DEDUP_MS = 100;

/**
 * 내 브라우저의 지금 탭을 그리고 누르기, 끌기, 글자를 보낸다. 동작은 `backend/docs/flow.md` 의 「로그인 화면」 이 갖는다.
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
  const [address, setAddress] = useState(startUrl ?? "");
  const areaRef = useRef<HTMLDivElement>(null);
  const imgRef = useRef<HTMLImageElement>(null);
  const keysRef = useRef<HTMLInputElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const { screenRef, dialogRef, triggerRef, expanded, toggle, closeOverlay } =
    useScreenFullscreen();
  const { frame, tabs, closed, notice, setNotice, send, reopen } =
    useScreenStream({ startUrl, onOpen, areaRef, addressRef, setAddress });
  const pointer = useScreenPointer({
    areaRef,
    imgRef,
    keysRef,
    send,
    frameHeight: frame?.height ?? 0,
  });

  return (
    <dialog
      ref={dialogRef}
      open
      aria-label="내 브라우저 크게 보기"
      onCancel={(event) => {
        event.preventDefault();
        closeOverlay();
      }}
      className={cn(
        "static m-0 max-h-none w-full max-w-none border-0 bg-background p-0 text-foreground",
        expanded && "fixed inset-0 h-dvh",
      )}
    >
      <section
        ref={screenRef}
        aria-label="로그인 화면"
        role={expanded ? "dialog" : undefined}
        aria-modal={expanded ? true : undefined}
        className={cn(
          "flex flex-col gap-3 bg-background",
          expanded && "fixed inset-0 z-50 h-dvh w-full overflow-y-auto p-3",
        )}
      >
        <Button
          ref={triggerRef}
          size="sm"
          variant="outline"
          className="self-end"
          onClick={() => void toggle()}
        >
          {expanded ? "전체 화면 닫기" : "전체 화면"}
        </Button>
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
          className={cn(
            "flex min-h-0 flex-col gap-2 md:flex-row",
            expanded && "flex-1",
          )}
        >
          <ScreenFrame
            areaRef={areaRef}
            imgRef={imgRef}
            expanded={expanded}
            frame={frame}
            closed={closed !== null}
            pointer={pointer}
          >
            <ScreenKeyboard keysRef={keysRef} send={send} closed={closed} />
          </ScreenFrame>
          <ScreenScrollControls
            send={send}
            height={frame?.height ?? 0}
            disabled={!frame || closed !== null}
          />
        </div>
      </section>
    </dialog>
  );
}

/** 글자 조합과 키 입력은 프레임 크기나 전체 화면 전환과 독립적으로 유지한다. */
function ScreenKeyboard({
  keysRef,
  send,
  closed,
}: {
  keysRef: React.RefObject<HTMLInputElement | null>;
  send: (input: ScreenInput) => void;
  closed: string | null;
}) {
  const composing = useRef(false);
  const pendingEnter = useRef(false);
  const enterSentAt = useRef(-Infinity);
  // 휴대폰의 Backspace 는 keydown 에 키 이름이 오지 않아 beforeinput 으로 잡는다.
  useEffect(() => {
    const keys = keysRef.current;
    if (!keys || closed !== null) return;
    const onBeforeInput = (event: InputEvent) => {
      if (event.inputType !== "deleteContentBackward" || event.isComposing)
        return;
      if (keys.value) return;
      event.preventDefault();
      send({ type: "key", key: "Backspace" });
    };
    keys.addEventListener("beforeinput", onBeforeInput);
    return () => keys.removeEventListener("beforeinput", onBeforeInput);
  }, [closed, send, keysRef]);

  // 조합을 마친 글자만 보내고 입력칸을 비운다. 조합 중인 한글은 조합이 끝난 뒤 보낸다.
  function flushText() {
    const keys = keysRef.current;
    if (composing.current || !keys?.value) return;
    textInputs(keys.value).forEach(send);
    keys.value = "";
  }

  function sendEnter() {
    pendingEnter.current = false;
    enterSentAt.current = performance.now();
    send({ type: "key", key: "Enter" });
  }

  function keyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (closed !== null) return;
    // 조합 중 Enter 는 조합한 글자 뒤에 한 번 보낸다.
    if (event.nativeEvent.isComposing) {
      if (event.key === "Enter") pendingEnter.current = true;
      return;
    }
    // Shift+Tab 은 화면 밖으로 초점을 옮기게 둔다.
    if (event.key === "Tab" && event.shiftKey) return;
    const input = screenKey(event.key);
    if (!input) return;
    event.preventDefault();
    flushText();
    if (event.key !== "Enter") return send(input);
    // 조합을 끝낸 Enter 가 한 번 더 오는 브라우저가 있어 겹친 것은 버린다.
    if (performance.now() - enterSentAt.current >= ENTER_DEDUP_MS) sendEnter();
  }

  // 조합이 끝난 자리에서 입력칸을 바로 비우면 입력기가 글자를 다시 넣는 브라우저가 있어 한 박자 미룬다.
  function compositionEnd() {
    composing.current = false;
    window.setTimeout(() => {
      flushText();
      if (pendingEnter.current) sendEnter();
    }, 0);
  }

  return (
    <input
      ref={keysRef}
      aria-label="화면에 글자 넣기"
      autoCapitalize="off"
      autoComplete="off"
      autoCorrect="off"
      spellCheck={false}
      className="absolute top-0 left-0 size-px text-base opacity-0"
      onKeyDown={keyDown}
      onInput={(event) => {
        if (!(event.nativeEvent as InputEvent).isComposing) flushText();
      }}
      onCompositionStart={() => {
        composing.current = true;
      }}
      onCompositionEnd={compositionEnd}
    />
  );
}
