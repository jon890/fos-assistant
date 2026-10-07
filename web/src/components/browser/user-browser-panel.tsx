"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { describeFailure } from "@/components/error-message";
import { DeleteBrowserDialog } from "@/components/browser/delete-browser-dialog";
import {
  BROWSER_STATUS,
  browserAction,
  fetchBrowser,
  type BrowserAction,
  type BrowserView,
} from "@/lib/browser-api";

/** 켜거나 끄는 동안 상태를 다시 읽는 간격이다. */
const POLL_MS = 2_000;

const LOADING_TEXT: Record<BrowserAction, string> = {
  create: "만드는 중…",
  start: "켜는 중…",
  stop: "끄는 중…",
  delete: "지우는 중…",
};

/** 상태를 읽는다. 읽지 못하면 `null` 이다. */
async function readView(): Promise<BrowserView | null> {
  try {
    const response = await fetchBrowser();
    return response.ok ? ((await response.json()) as BrowserView) : null;
  } catch {
    return null;
  }
}

/** 사용자 한 사람의 브라우저를 만들고, 켜고, 끄고, 지운다. 오류 코드는 그리지 않는다. */
export function UserBrowserPanel() {
  const [view, setView] = useState<BrowserView | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [pending, setPending] = useState<BrowserAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  function apply(next: BrowserView | null) {
    if (next === null) setLoadError(true);
    else {
      setView(next);
      setLoadError(false);
    }
  }

  useEffect(() => {
    void readView().then(apply);
  }, []);

  const status = view?.exists ? view.status : undefined;
  const moving = status === "STARTING" || status === "STOPPING";

  // 켜거나 끄는 동안은 응답이 올 때마다 다음 읽기를 건다.
  useEffect(() => {
    if (!moving) return;
    const timer = setTimeout(() => void readView().then(apply), POLL_MS);
    return () => clearTimeout(timer);
  }, [moving, view]);

  async function run(action: BrowserAction) {
    if (pending !== null) return;
    setPending(action);
    setError(null);
    try {
      const response = await browserAction(action);
      if (!response.ok) setError(await describeFailure(response));
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    apply(await readView());
    setPending(null);
    setConfirming(false);
  }

  if (loadError && view === null) {
    return (
      <Notice variant="error" role="alert">
        브라우저 상태를 읽지 못했어요. 화면을 다시 열어 주세요.
      </Notice>
    );
  }
  if (view === null) {
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  }
  if (!view.enabled) {
    return <Notice variant="info">아직 준비 중이에요.</Notice>;
  }

  const minutes = Math.max(1, Math.round((view.idleTimeoutSeconds ?? 0) / 60));

  return (
    <div className="space-y-4">
      {status === undefined ? (
        <>
          <EmptyState
            title="아직 브라우저가 없어요"
            description="브라우저를 만들면 연결에 필요한 사이트에 로그인해 둘 수 있어요."
          />
          <Button
            onClick={() => void run("create")}
            loading={pending === "create"}
            loadingText={LOADING_TEXT.create}
          >
            브라우저 만들기
          </Button>
        </>
      ) : (
        <section
          aria-label="내 브라우저 상태"
          className="space-y-3 rounded-md border border-border p-4"
        >
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-semibold">상태</h2>
            <Badge
              variant={BROWSER_STATUS[status].variant}
              data-testid="browser-status"
            >
              {BROWSER_STATUS[status].label}
            </Badge>
          </div>
          {status === "FAILED" ? (
            <p className="text-sm text-muted-foreground">
              잠시 뒤 다시 켜 주세요.
            </p>
          ) : null}
          <p className="text-sm text-muted-foreground">
            쓰지 않으면 {minutes}분 뒤 저절로 꺼져요.
          </p>
          <div className="flex flex-wrap gap-2">
            {status === "STOPPED" || status === "FAILED" ? (
              <Button
                disabled={pending !== null}
                onClick={() => void run("start")}
                loading={pending === "start"}
                loadingText={LOADING_TEXT.start}
              >
                켜기
              </Button>
            ) : null}
            {status === "RUNNING" || status === "FAILED" ? (
              <Button
                variant="outline"
                disabled={pending !== null}
                onClick={() => void run("stop")}
                loading={pending === "stop"}
                loadingText={LOADING_TEXT.stop}
              >
                끄기
              </Button>
            ) : null}
            <Button
              variant="destructive"
              disabled={pending !== null || moving}
              onClick={() => setConfirming(true)}
            >
              지우기
            </Button>
          </div>
        </section>
      )}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      {confirming ? (
        <DeleteBrowserDialog
          description="로그인한 사이트에서 모두 로그아웃돼요."
          busy={pending === "delete"}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void run("delete")}
        />
      ) : null}
    </div>
  );
}
