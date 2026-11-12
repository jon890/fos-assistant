import type { DelegationLine } from "@/lib/delegation";
import type { AutoVaultState } from "@/lib/artifact-vault-api";
import type { ActivitySummary } from "@/lib/chat-event";
import type { AnswerReveal } from "./answer-reveal";

/** 대화에 붙은 사진 한 장이다. `ChatDtos.AttachmentView` 를 그대로 받는다 */
export type MessageAttachment = {
  id: number;
  originalName: string;
  byteSize: number;
  visible: boolean;
  expiresAt: string;
};

/** 답의 turn 이 결과물 폴더에 만든 HTML 하나다. `ChatDtos.ArtifactView` 를 그대로 받는다 */
export type MessageArtifact = {
  /** 대화 결과물 폴더 안의 상대 경로다 */
  path: string;
  byteSize: number;
  /** 보관 기간이 지나 파일이 지워졌다 */
  deleted: boolean;
  /** 자동 보관 상태다. 자동 보관 대상이 아니면 null 이다. 옛 서버 응답에는 없다 */
  autoVault?: AutoVaultState | null;
  /** 자동 보관이 실패한 까닭의 오류 코드다 */
  autoVaultError?: string | null;
};

/** 결과 전달 묶음의 상태다. `ChatDtos.DeliveryView` 를 그대로 받는다 */
export type MessageDelivery = {
  id: number;
  status: "DELIVERING" | "DELIVERED" | "FAILED" | "STOPPED";
};

export type SourceReadSummary = {
  completedCount: number;
  urls: string[];
  /** 결과에서 주소를 확인하지 못한 성공 호출의 요청 주소다. 구 서버 응답에는 없다. */
  requestedUrls?: string[];
  unresolvedCount: number;
  observationComplete: boolean;
};

export type Turn = {
  id: number | string;
  /** 아직 저장되지 않은 답이 받은 글을 풀어 보이는 장치다. 저장된 메시지에는 없다 */
  reveal?: AnswerReveal;
  /** `SYSTEM` 은 대화에 남는 알림 줄이다. 위임 결과가 도착했거나 자동 turn 한도에 닿았을 때 생긴다 */
  role: "USER" | "ASSISTANT" | "SYSTEM";
  content: string;
  senderName: string | null;
  createdAt?: string;
  executionId?: number | null;
  /** 이 답이 여러 실행으로 만들어졌는지 서버가 알려준다 */
  hasChildren?: boolean;
  /** 앞 provider 가 막혀 넘어갔으면 그 답을 만든 provider 와 모델. 아니면 null 이다 */
  switchedTo?: string | null;
  /** 이 메시지에 붙은 사진들. 지워진 것도 자리를 남기려고 담는다 */
  attachments?: MessageAttachment[];
  /** 이 답의 turn 이 만든 결과물 파일들. 보관 기간이 지난 것도 자리를 남기려고 담는다 */
  artifacts?: MessageArtifact[];
  activity?: ActivitySummary | null;
  status?: "SUCCEEDED" | "FAILED" | "CANCELLED" | "RUNNING" | null;
  replacesMessageId?: number | null;
  /** 이 알림 줄이 결과 묶음의 마지막 알림 줄이면 그 묶음의 상태 */
  delivery?: MessageDelivery | null;
  sourceReads?: SourceReadSummary | null;
  /** 이 답의 실행이 바로 맡긴 위임이다. 옛 서버 응답에는 없다 */
  delegations?: DelegationLine[];
  /** 이 위임 결과 알림 줄이 전한 결과다. 있으면 저장된 글 대신 결과마다 이름표 줄을 그린다 */
  deliveredResults?: DelegationLine[];
};
