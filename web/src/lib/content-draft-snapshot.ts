import { callConnectorApi } from "@/lib/connector-action";

export const CONTENT_DRAFT_JOB_STATUSES = [
  "PREVIEW_READY",
  "DISPATCHED",
  "SAVING",
  "SUCCEEDED",
  "FAILED",
  "UNKNOWN",
  "STALE",
] as const;

export type ContentDraftJobStatus = (typeof CONTENT_DRAFT_JOB_STATUSES)[number];

/** 더 바뀌지 않는 job 상태다. 이 상태가 되면 폴링을 멈춘다. */
export function isContentDraftTerminal(status: ContentDraftJobStatus) {
  return ["SUCCEEDED", "FAILED", "UNKNOWN", "STALE"].includes(status);
}

/** 원고 저장 승인 줄에 붙은 요약이다. `errorCode` 는 내부 값이라 화면에 그리지 않는다. */
export type ContentDraftSummary = {
  snapshotId: string;
  draftId: string;
  revision: number;
  conversationId: string;
  status: ContentDraftJobStatus;
  stage: string | null;
  saveClicked: boolean;
  errorCode: string | null;
};

type DraftIds = Pick<
  ContentDraftSummary,
  "conversationId" | "draftId" | "snapshotId"
>;

export type ContentDraftBlock =
  | { type: "paragraph"; text: string }
  | { type: "image"; assetId: string; caption?: string | null }
  | { type: "sticker"; code: string }
  | { type: "map"; name: string; address: string };

/** 승인 전에 보일 canonical 원고다. 응답의 `snapshotHash` 는 내부 값이라 화면이 읽지 않는다. */
export type ContentDraftSnapshot = {
  revision: number;
  preview: {
    title: string;
    category: string | null;
    tags: string[];
    blocks: ContentDraftBlock[];
  };
  assetIds: string[];
  omittedAssetIds: string[];
};

/** Control Plane 이 보관한 job 상태다. 응답의 `jobId` 와 `errorCode` 는 화면이 읽지 않는다. */
export type ContentDraftJob = Omit<
  ContentDraftSummary,
  "conversationId" | "errorCode"
> & { actionId: string };

/** 원고가 바뀌었거나 지워졌거나 남의 것이라 보일 수 없다는 code 다. 화면은 모두 같은 안내로 그린다. */
export const CONTENT_DRAFT_GONE_CODES = [
  "CONTENT_DRAFT_STALE",
  "CONTENT_DRAFT_GONE",
  "CONVERSATION_NOT_FOUND",
];

const snapshotPath = (ids: DraftIds) =>
  `/api/chat/conversations/${ids.conversationId}/content-drafts/${ids.draftId}/snapshots/${ids.snapshotId}`;

export const readContentDraftSnapshot = (ids: DraftIds) =>
  callConnectorApi<ContentDraftSnapshot>(snapshotPath(ids));

export const readContentDraftJob = (ids: DraftIds) =>
  callConnectorApi<ContentDraftJob>(`${snapshotPath(ids)}/job`);

/** 그 대화의 원고 job 을 최근 순으로 한 페이지 읽는다. 원격 상태를 조회하지 않는다. */
export const readContentDraftJobs = (
  conversationId: string,
  before: string | null,
) =>
  callConnectorApi<{
    items: ContentDraftJob[];
    nextBeforeSnapshotId: string | null;
  }>(
    `/api/chat/conversations/${conversationId}/content-draft-jobs${
      before === null ? "" : `?beforeSnapshotId=${before}`
    }`,
  );
