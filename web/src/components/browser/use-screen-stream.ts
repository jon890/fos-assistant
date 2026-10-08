"use client";

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type RefObject,
} from "react";
import { describeFailure } from "@/components/error-message";
import { openBrowserScreen, sendScreenInput } from "@/lib/browser-api";
import { readEventStream } from "@/lib/stream";
import type { ScreenTab } from "@/components/browser/browser-screen-toolbar";
import { resizeFor, type ScreenInput } from "@/components/browser/screen-input";

export type Frame = { data: string; width: number; height: number };

/** `closed` 없이 끊긴 SSE 를 다시 열기 전에 기다리는 시간이다. 연이어 이만큼 실패하면 닫는다. */
const RECONNECT_MS = [1_000, 2_000, 4_000];

const CLOSED: Record<string, string> = {
  replaced: "다른 창에서 화면을 열었어요.",
  stopped: "브라우저가 꺼졌어요.",
  timeout: "오래 쓰지 않아 화면을 닫았어요.",
};
const LOST = "화면 연결이 끊겼어요. 다시 열어 주세요.";

/**
 * 로그인 화면 SSE 의 수명과 입력 전송이다. 동작은 `docs/backend/user-browser.md` 의 「로그인 화면」 이 갖는다.
 * 시작 주소는 첫 연결에만 쓴다. 다시 열 때는 지금 탭을 그대로 본다.
 */
export function useScreenStream({
  startUrl,
  onOpen,
  areaRef,
  addressRef,
  setAddress,
}: {
  startUrl: string | null;
  onOpen: () => void;
  areaRef: RefObject<HTMLDivElement | null>;
  addressRef: RefObject<HTMLInputElement | null>;
  setAddress: (url: string) => void;
}) {
  const [attempt, setAttempt] = useState(0);
  const [frame, setFrame] = useState<Frame | null>(null);
  const [tabs, setTabs] = useState<ScreenTab[]>([]);
  const [closed, setClosed] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** 열린 연결이 있을 때만 참이다. 끊긴 동안의 입력은 버린다. */
  const open = useRef(false);
  /** 연결마다 하나씩 늘린다. 앞 연결에 보낸 입력의 오류는 화면을 닫지 않는다. */
  const generation = useRef(0);
  const stream = useRef<AbortController | null>(null);
  const queue = useRef<Promise<void>>(Promise.resolve());
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
      const sentIn = generation.current;
      const current = () => open.current && sentIn === generation.current;
      queue.current = queue.current.then(async () => {
        if (!current()) return;
        try {
          const response = await sendScreenInput(input);
          if (response.ok) return;
          const message = await describeFailure(response);
          if (!current()) return;
          if (response.status === 409) close(message);
          else setNotice(message);
        } catch {
          if (current())
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
  }, [areaRef, send]);

  useEffect(() => {
    const controller = new AbortController();
    stream.current = controller;
    const wait = (ms: number) =>
      new Promise<void>((resolve) => {
        const timer = window.setTimeout(resolve, ms);
        controller.signal.addEventListener(
          "abort",
          () => {
            window.clearTimeout(timer);
            resolve();
          },
          { once: true },
        );
      });
    const onEvent = (data: unknown, name: string) => {
      if (name === "frame") setFrame(data as Frame);
      else if (name === "tabs") {
        const next = data as ScreenTab[];
        setTabs(next);
        const active = next.find((tab) => tab.active);
        if (active && document.activeElement !== addressRef.current)
          setAddress(active.url);
      }
    };
    const run = async () => {
      let url = attempt === 0 ? startUrl : null;
      let retries = 0;
      while (!controller.signal.aborted) {
        try {
          const response = await openBrowserScreen(url, controller.signal);
          if (!response.ok) return close(await describeFailure(response));
          generation.current += 1;
          open.current = true;
          url = null;
          onOpen();
          sentWidth.current = 0;
          resize();
          await readEventStream<unknown>(response, (data, name) => {
            retries = 0;
            // 닫힘은 서버가 SSE 를 끝내기를 기다리지 않고 바로 그린다.
            if (name === "closed")
              close(CLOSED[(data as { reason: string }).reason] ?? LOST);
            else onEvent(data, name);
          });
        } catch {
          // 연결이 깨졌다. 끊은 것이 이 화면이 아니면 아래에서 판단한다.
        }
        open.current = false;
        if (controller.signal.aborted) return;
        if (retries >= RECONNECT_MS.length) return close(LOST);
        // 말없이 끊겼다. 화면을 둔 채 기다렸다가 다시 연다.
        await wait(RECONNECT_MS[retries++]!);
      }
    };
    void run();
    return () => {
      open.current = false;
      controller.abort();
    };
  }, [attempt, startUrl, onOpen, close, resize, addressRef, setAddress]);

  useEffect(() => {
    const area = areaRef.current;
    if (!area) return;
    let timer = 0;
    const observer = new ResizeObserver(() => {
      window.clearTimeout(timer);
      timer = window.setTimeout(resize, 300);
    });
    observer.observe(area);
    return () => {
      observer.disconnect();
      window.clearTimeout(timer);
    };
  }, [areaRef, resize]);

  const reopen = useCallback(() => {
    setClosed(null);
    setNotice(null);
    setFrame(null);
    setTabs([]);
    setAttempt((value) => value + 1);
  }, []);

  return { frame, tabs, closed, notice, setNotice, send, reopen };
}
