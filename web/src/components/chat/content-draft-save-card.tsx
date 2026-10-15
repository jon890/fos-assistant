"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  approveConnectorAction,
  rejectConnectorAction,
  type ConnectorAction,
  type ConnectorActionStatus,
} from "@/lib/connector-action";
import {
  CONTENT_DRAFT_GONE_CODES,
  isContentDraftTerminal,
  readContentDraftJob,
  readContentDraftSnapshot,
  type ContentDraftBlock,
  type ContentDraftJobStatus,
  type ContentDraftSnapshot,
  type ContentDraftSummary,
} from "@/lib/content-draft-snapshot";

/** 화면이 열린 동안 저장 중인 job 을 다시 읽는 간격이다. */
const POLL_MS = 3000;

/** job 이 아직 승인 전이면 승인 줄의 상태로 카드를 정한다. 승인 응답이 job 보다 먼저 온다. */
const FROM_ACTION: Record<ConnectorActionStatus, ContentDraftJobStatus> = {
  PENDING: "PREVIEW_READY",
  EXECUTING: "DISPATCHED",
  SUCCEEDED: "SUCCEEDED",
  FAILED: "FAILED",
  UNKNOWN: "UNKNOWN",
  REJECTED: "STALE",
  EXPIRED: "STALE",
};

const SAVING = ["info", "임시저장하는 중이에요."] as const;
const RESULTS: Partial<
  Record<
    ContentDraftJobStatus,
    readonly ["info" | "success" | "warning" | "error", string]
  >
> = {
  DISPATCHED: SAVING,
  SAVING,
  SUCCEEDED: [
    "success",
    "임시저장했어요. 블로그의 임시저장 글에서 확인해 주세요.",
  ],
  FAILED: ["error", "지금은 저장할 수 없어요. 원고는 그대로 남아 있어요."],
  // 결과를 모를 때 다시 저장하면 같은 글이 두 번 남을 수 있다. 확인할 곳만 알린다.
  UNKNOWN: [
    "warning",
    "임시저장했는지 확인하지 못했어요. 블로그의 임시저장 글에서 직접 확인해 주세요.",
  ],
  STALE: [
    "warning",
    "원고가 바뀌었거나 볼 수 없어요. 새 미리보기를 만들어 주세요.",
  ],
};

/** 실패 사유 코드를 사용자 문구로 옮긴다. 모르는 코드는 덧붙이지 않는다. */
const FAILURE_REASONS: Record<string, string> = {
  screen_blocked: "글쓰기 화면에 알림이 떠 있었어요.",
  title_cursor_covered:
    "글쓰기 화면의 다른 요소가 제목 자리를 가리고 있었어요.",
  body_cursor_covered: "글쓰기 화면의 다른 요소가 본문 자리를 가리고 있었어요.",
  title_cursor_failed: "글쓰기 화면의 제목 자리에 글을 넣지 못했어요.",
  body_cursor_failed: "글쓰기 화면의 본문 자리에 글을 넣지 못했어요.",
  title_mismatch: "글쓰기 화면에 제목이 맞게 들어가지 않았어요.",
  body_mismatch: "글쓰기 화면에 본문이 맞게 들어가지 않았어요.",
};

/** 저장 기능이 꺼져 실행 없이 거절된 줄이다. 새 미리보기를 만들어도 같은 거절이라 다시 만들라고 하지 않는다. */
const UNSUPPORTED = [
  "warning",
  "지금은 저장할 수 없어요. 원고는 그대로 남아 있어요.",
] as const;
const DECLINED = ["info", "임시저장을 거절했어요."] as const;

/**
 * STALE 은 사용자 거절, 실행 없는 거절, 원고 변경·만료를 함께 담는다. 오류 code 는 그리지 않고 문구만 고른다.
 * job 만 있는 카드는 승인 줄 상태를 몰라 사용자 거절을 원고 변경과 구분하지 못한다.
 */
function staleNotice(
  draft: ContentDraftSummary,
  actionStatus: ConnectorActionStatus,
) {
  if (draft.errorCode === "not_executable") return UNSUPPORTED;
  if (actionStatus === "REJECTED" && draft.errorCode === null) return DECLINED;
  return RESULTS.STALE;
}

/** 사진은 주인 확인을 거치는 기존 대화 첨부 경로로 연다. 정규형 번호가 아니면 링크를 만들지 않는다. */
function Photos({
  ids,
  conversationId,
}: {
  ids: string[];
  conversationId: string;
}) {
  return ids.map((id, index) => (
    <span key={`${id}-${index}`}>
      {index > 0 ? ", " : null}
      {/^[1-9][0-9]*$/.test(id) ? (
        <a
          href={`/api/chat/conversations/${conversationId}/attachments/${id}`}
          target="_blank"
          rel="noopener noreferrer"
          className="underline underline-offset-4 hover:text-foreground-soft"
        >
          사진 {id}
        </a>
      ) : (
        `사진 ${id}`
      )}
    </span>
  ));
}

/** 문단은 글자 그대로 그린다. 에이전트가 쓴 글이라 HTML 이나 마크다운으로 읽지 않는다. */
function Block({
  block,
  conversationId,
}: {
  block: ContentDraftBlock;
  conversationId: string;
}) {
  switch (block.type) {
    case "paragraph":
      return <p className="whitespace-pre-wrap break-words">{block.text}</p>;
    case "image":
      return (
        <p className="break-words text-muted-foreground">
          <Photos ids={[block.assetId]} conversationId={conversationId} />
          {block.caption ? ` · ${block.caption}` : null}
        </p>
      );
    case "sticker":
      return <p className="text-muted-foreground">스티커 {block.code}</p>;
    case "map":
      return (
        <p className="break-words text-muted-foreground">
          지도 · {block.name} ({block.address})
        </p>
      );
  }
}

function SnapshotView({
  snapshot,
  conversationId,
}: {
  snapshot: ContentDraftSnapshot;
  conversationId: string;
}) {
  const { preview } = snapshot;
  const meta = [
    preview.category ? `분류 ${preview.category}` : null,
    preview.tags.length > 0 ? `태그 ${preview.tags.join(", ")}` : null,
  ].filter(Boolean);
  const photoLines = [
    ["content-draft-assets", "넣는 사진", snapshot.assetIds],
    ["content-draft-omitted", "넣지 않는 사진", snapshot.omittedAssetIds],
  ] as const;
  return (
    <>
      <div
        data-testid="content-draft-preview"
        className="flex flex-col gap-2 rounded-md bg-muted px-3 py-2 text-sm"
      >
        <p className="break-words font-medium">{preview.title}</p>
        {meta.length > 0 ? (
          <p className="break-words text-xs text-muted-foreground">
            {meta.join(" · ")}
          </p>
        ) : null}
        {preview.blocks.map((block, index) => (
          <Block key={index} block={block} conversationId={conversationId} />
        ))}
      </div>
      {/* 넣지 않는 사진도 따로 보인다. 모든 사진을 넣었다고 말하지 않는다. */}
      {photoLines.map(([testId, label, ids]) =>
        ids.length > 0 ? (
          <p key={testId} data-testid={testId} className="break-words text-xs">
            <span className="text-muted-foreground">{label}: </span>
            <Photos ids={ids} conversationId={conversationId} />
          </p>
        ) : null,
      )}
    </>
  );
}

/**
 * 저장 중인 job 을 화면이 열린 동안 GET 으로만 다시 읽는다. 끝난 상태가 오면 `polling` 이 거짓이 되어 멈춘다.
 * 원고가 바뀌었거나 남의 것이면 STALE 로 본다.
 */
function useJobPolling(
  polling: boolean,
  { conversationId, draftId, snapshotId }: ContentDraftSummary,
  /** `useState` 의 setter 라 바뀌지 않는다 */
  setJob: (status: ContentDraftJobStatus) => void,
) {
  useEffect(() => {
    if (!polling) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function tick() {
      const result = await readContentDraftJob({
        conversationId,
        draftId,
        snapshotId,
      });
      if (stopped) return;
      if (result.ok) setJob(result.data.status);
      else if (CONTENT_DRAFT_GONE_CODES.includes(result.code)) setJob("STALE");
      timer = setTimeout(() => void tick(), POLL_MS);
    }
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [polling, conversationId, draftId, snapshotId, setJob]);
}

/**
 * 원고 임시저장을 승인받는 카드다.
 *
 * <p>승인 전에는 snapshot 을 읽어 canonical 원고를 보이고 승인과 거절만 받는다. 상시 허락과 대화 허락, 「모두 승인」 에
 * 들어가지 않는다. 승인 뒤와 다시 연 화면에서는 job 을 GET 으로만 읽고 끝난 상태가 되면 멈춘다. 저장을 다시
 * 보내는 단추는 어느 상태에도 없다. jobId, 오류 code, snapshotHash 같은 내부 값은 그리지 않는다.
 *
 * <p>원고가 바뀌었거나 지워졌거나 남의 것이면 본문과 단추를 지우고 안내만 남긴다.
 */
export function ContentDraftSaveCard({
  action: { actionId, status: actionStatus },
  draft,
  onChanged,
  onDismiss,
}: {
  /** job 만 있는 줄은 job 상태가 카드를 정하므로 `status` 에 `PENDING` 을 넘긴다. */
  action: Pick<ConnectorAction, "actionId" | "status">;
  draft: ContentDraftSummary;
  onChanged(next: ConnectorAction | null): void;
  onDismiss(): void;
}) {
  const { conversationId, draftId, snapshotId } = draft;
  const [job, setJob] = useState<ContentDraftJobStatus | null>(null);
  const [preview, setPreview] = useState<
    ContentDraftSnapshot | "gone" | "unavailable" | null
  >(null);
  const [sending, setSending] = useState<"approve" | "reject" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const base =
    draft.status === "PREVIEW_READY" ? FROM_ACTION[actionStatus] : draft.status;
  const status = job && !isContentDraftTerminal(base) ? job : base;
  const waiting = status === "PREVIEW_READY";
  useJobPolling(status === "DISPATCHED" || status === "SAVING", draft, setJob);

  useEffect(() => {
    if (!waiting) return;
    let stale = false;
    void readContentDraftSnapshot({ conversationId, draftId, snapshotId }).then(
      (result) => {
        if (stale) return;
        const gone =
          !result.ok && CONTENT_DRAFT_GONE_CODES.includes(result.code);
        setPreview(result.ok ? result.data : gone ? "gone" : "unavailable");
      },
    );
    return () => {
      stale = true;
    };
  }, [waiting, conversationId, draftId, snapshotId]);

  async function send(kind: "approve" | "reject") {
    if (sending) return;
    setSending(kind);
    setError(null);
    // 원고 줄은 기간 허락과 대화 허락 없이 이번 한 번만 승인한다.
    const result =
      kind === "approve"
        ? await approveConnectorAction(actionId, null)
        : await rejectConnectorAction(actionId);
    setSending(null);
    if (result.ok) return onChanged(result.data);
    setError(result.message);
    onChanged(null);
  }

  const shown =
    status === "PREVIEW_READY" && preview === "gone" ? "STALE" : status;
  const reason =
    shown === "FAILED" && draft.errorReason
      ? FAILURE_REASONS[draft.errorReason]
      : undefined;
  const notice =
    shown === "STALE" && preview !== "gone"
      ? staleNotice(draft, actionStatus)
      : RESULTS[shown];
  const result =
    notice && reason
      ? ([notice[0], `${notice[1]} ${reason}`] as const)
      : notice;
  const loaded = typeof preview === "object" && preview !== null;

  return (
    <section
      data-testid="content-draft-card"
      data-status={shown}
      className="flex min-w-0 flex-col gap-3 rounded-lg border border-border bg-card p-4 shadow-card"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="text-sm font-medium">블로그 임시저장</h3>
        {shown === "STALE" ? null : (
          <Badge variant="outline" data-testid="content-draft-revision">
            원고 {draft.revision}번째 버전
          </Badge>
        )}
      </div>
      {shown === "PREVIEW_READY" ? (
        <>
          <p className="text-sm text-muted-foreground">
            에이전트가 이 원고를 블로그에 임시저장하려고 해요. 내용을 확인해
            주세요.
          </p>
          {loaded ? (
            <SnapshotView snapshot={preview} conversationId={conversationId} />
          ) : preview === "unavailable" ? (
            <Notice variant="warning" role="status">
              원고를 불러오지 못했어요. 잠시 뒤 대화를 다시 열어 주세요.
            </Notice>
          ) : null}
          <div className="flex flex-wrap items-center gap-2">
            {/* 원고를 읽지 못했으면 보지 않은 글을 승인하지 않게 거절만 남긴다. */}
            {loaded ? (
              <Button
                size="sm"
                data-testid="content-draft-approve"
                disabled={sending !== null}
                loading={sending === "approve"}
                onClick={() => void send("approve")}
              >
                승인
              </Button>
            ) : null}
            <Button
              size="sm"
              variant="outline"
              data-testid="content-draft-reject"
              disabled={sending !== null}
              loading={sending === "reject"}
              onClick={() => void send("reject")}
            >
              거절
            </Button>
          </div>
        </>
      ) : null}
      {result ? (
        <Notice
          variant={result[0]}
          role="status"
          data-testid="content-draft-result"
        >
          {result[1]}
        </Notice>
      ) : null}
      {isContentDraftTerminal(shown) ? (
        <div>
          <Button
            size="sm"
            variant="outline"
            data-testid="content-draft-dismiss"
            onClick={onDismiss}
          >
            닫기
          </Button>
        </div>
      ) : null}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </section>
  );
}
