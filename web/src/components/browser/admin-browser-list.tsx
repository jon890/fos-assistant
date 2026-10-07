"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { describeAdminError } from "@/components/error-message";
import { DeleteBrowserDialog } from "@/components/browser/delete-browser-dialog";
import { formatWhen } from "@/lib/format";
import {
  BROWSER_STATUS,
  adminBrowserAction,
  fetchAdminBrowsers,
  type AdminBrowser,
} from "@/lib/browser-api";

/** 목록을 읽는다. 읽지 못하면 `null` 이다. */
async function readBrowsers(): Promise<AdminBrowser[] | null> {
  try {
    const response = await fetchAdminBrowsers();
    return response.ok ? ((await response.json()) as AdminBrowser[]) : null;
  } catch {
    return null;
  }
}

/** 실패한 응답을 관리 화면 문구로 바꾼다. */
async function failureOf(response: Response): Promise<string> {
  const payload = (await response.json().catch(() => ({}))) as {
    code?: string;
    message?: string;
  };
  return describeAdminError(
    payload.code ?? "INTERNAL_ERROR",
    payload.message ?? "요청을 처리하지 못했어요.",
  );
}

/** 관리자 영역의 모든 사용자 브라우저 목록이다. 오류 코드는 여기서만 그린다. */
export function AdminBrowserList() {
  const [browsers, setBrowsers] = useState<AdminBrowser[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<AdminBrowser | null>(null);

  function apply(next: AdminBrowser[] | null) {
    if (next === null) setLoadError(true);
    else {
      setBrowsers(next);
      setLoadError(false);
    }
  }

  useEffect(() => {
    void readBrowsers().then(apply);
  }, []);

  async function run(browser: AdminBrowser, action: "stop" | "delete") {
    if (pending !== null) return;
    setPending(browser.id);
    setError(null);
    try {
      const response = await adminBrowserAction(browser.id, action);
      if (!response.ok) setError(await failureOf(response));
    } catch {
      setError("요청을 보내지 못했어요. 잠시 뒤 다시 해 주세요.");
    }
    apply(await readBrowsers());
    setPending(null);
    setDeleting(null);
  }

  return (
    <div className="mx-auto w-full max-w-3xl">
      <h1 className="mb-4 text-xl font-semibold">브라우저</h1>
      {loadError && browsers === null ? (
        <Notice variant="error" role="alert">
          브라우저 목록을 읽지 못했어요. 화면을 다시 열어 주세요.
        </Notice>
      ) : browsers === null ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : browsers.length === 0 ? (
        <EmptyState
          title="만든 브라우저가 없어요"
          description="사용자가 내 브라우저를 만들면 여기에 나타나요."
        />
      ) : (
        <ul className="space-y-2">
          {browsers.map((browser) => (
            <li
              key={browser.id}
              data-testid="admin-browser"
              className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-border p-3"
            >
              <div className="min-w-0 space-y-1 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="font-medium break-all">{browser.userName}</p>
                  <Badge variant={BROWSER_STATUS[browser.status].variant}>
                    {BROWSER_STATUS[browser.status].label}
                  </Badge>
                </div>
                <p className="text-muted-foreground">
                  {browser.lastActiveAt
                    ? `마지막 사용 ${formatWhen(browser.lastActiveAt)}`
                    : "아직 쓰지 않았어요"}
                </p>
                {browser.lastError ? (
                  <p className="break-all text-destructive">
                    오류 코드 {browser.lastError}
                  </p>
                ) : null}
              </div>
              <div className="flex gap-2">
                {browser.status === "RUNNING" || browser.status === "FAILED" ? (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={pending !== null}
                    loading={pending === browser.id && deleting === null}
                    loadingText="끄는 중…"
                    onClick={() => void run(browser, "stop")}
                  >
                    끄기
                  </Button>
                ) : null}
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={pending !== null}
                  onClick={() => setDeleting(browser)}
                >
                  지우기
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : null}
      {deleting ? (
        <DeleteBrowserDialog
          description={`${deleting.userName} 님은 로그인한 사이트에서 모두 로그아웃돼요.`}
          busy={pending === deleting.id}
          onCancel={() => setDeleting(null)}
          onConfirm={() => void run(deleting, "delete")}
        />
      ) : null}
    </div>
  );
}
