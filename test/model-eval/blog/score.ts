import type {
  BackendPipelineEvent,
  EmbeddedEvent,
  ReferencedEvent,
} from "../../support/media-pipeline-log.ts";

/** 합성 사진 하나의 정답 허용 표기다. */
export type PhotoAnswer = { id: string; accept: string[] };
/** 평가가 보낸 메시지 하나다. `attachmentIds` 와 `photoIds` 는 같은 자리끼리 짝이다. */
export type RunMessage = { executionId: number | null; attachmentIds: number[]; photoIds: string[] };
/** 새 대화 하나에서 시나리오를 한 번 돌린 기록이다. */
export type RunRecord = {
  scenarioId: string;
  repeat: number;
  conversationId: string | null;
  messages: RunMessage[];
  error?: string;
};
export type RunOutcome = "PASS" | "FAIL" | "INCONCLUSIVE";
export type PhotoIssue = "참조 누락" | "조회 누락" | "판독 실패" | "원고 누락";
export type PhotoResult = { photoId: string; attachmentId: number; issues: PhotoIssue[] };
export type RunScore = { outcome: RunOutcome; reasons: string[]; notes: string[]; photos: PhotoResult[] };

export type ScoreInput = {
  run: RunRecord;
  /** 비공개 재현이면 정답이 없어 `null` 이다. */
  answers: Map<string, PhotoAnswer> | null;
  events: BackendPipelineEvent[];
  /** 마지막 메시지 실행 트리의 루트와 모든 자손 실행 번호다. */
  executionIds: number[];
  /** 마지막 실행의 트리 `root.status` 다. */
  status: string | null;
  /** 실행 트리에서 실패한 네이버 블로그 도구 이름이다. */
  failedTools: string[];
  /** 실행 트리를 어딘가 잘라 자손 실행을 모두 보지 못했다. */
  truncated: boolean;
  preview: string | null;
};

const ENTITIES: Record<string, string> = {
  "&amp;": "&",
  "&lt;": "<",
  "&gt;": ">",
  "&quot;": '"',
  "&#39;": "'",
};

/** 태그를 지우고 엔티티를 풀고 공백을 모두 지운다. 정답 비교는 양쪽 모두 이 함수를 거친다. */
export function normalize(text: string): string {
  return text
    .replace(/<[^>]*>/g, "")
    .replace(/&(?:amp|lt|gt|quot|#39);/g, (entity) => ENTITIES[entity])
    .replace(/\s+/g, "");
}

/** `attachments/<번호>` 로 그 첨부를 부르는가. `12` 가 `123` 에 맞지 않게 뒤 숫자를 막는다. */
function callsAttachment(html: string, attachmentId: number): boolean {
  return new RegExp(`attachments/${attachmentId}(?![0-9])`).test(html);
}

/** 결과물 HTML 가운데 이 실행의 첨부를 하나라도 부르는 마지막 것을 고른다. */
export function previewOf(htmls: string[], attachmentIds: number[]): string | null {
  for (let i = htmls.length - 1; i >= 0; i--) {
    if (attachmentIds.some((id) => callsAttachment(htmls[i], id))) return htmls[i];
  }
  return null;
}

function idsOf(event: BackendPipelineEvent): string[] {
  if (event.stage === "inspected") return [event.assetId];
  const ids = event.assets.map((asset) => asset.assetId);
  return event.stage === "embedded" ? [...ids, ...event.missing] : ids;
}

/**
 * 마지막 메시지가 실행 입력을 만들 때 남긴 `embedded` 와 `referenced` 를 사건 순서로 고른다.
 * 한 실행 입력을 만들 때 `embedded` 다음에 바로 `referenced` 가 남는다. 앞 메시지의 `embedded` 와,
 * 그 바로 뒤에 오며 그 메시지보다 더 앞의 사진만 담은 `referenced` 까지를 앞 메시지의 몫으로 보고 그 뒤에서 고른다.
 */
function lastMessageEvents(
  events: BackendPipelineEvent[],
  messageOf: Map<string, number>,
  lastIndex: number,
): { embedded: EmbeddedEvent | null; referenced: ReferencedEvent | null } {
  let boundary = -1;
  events.forEach((event, i) => {
    if (event.stage !== "embedded") return;
    const owners = idsOf(event)
      .map((id) => messageOf.get(id))
      .filter((owner): owner is number => owner !== undefined && owner < lastIndex);
    if (owners.length === 0) return;
    boundary = Math.max(boundary, i);
    const next = events[i + 1];
    const owner = Math.min(...owners);
    if (
      next?.stage === "referenced" &&
      next.assets.every((asset) => (messageOf.get(asset.assetId) ?? Infinity) < owner)
    )
      boundary = Math.max(boundary, i + 1);
  });
  let embedded: EmbeddedEvent | null = null;
  let referenced: ReferencedEvent | null = null;
  for (const event of events.slice(boundary + 1)) {
    if (event.stage === "embedded") embedded = event;
    else if (event.stage === "referenced") referenced = event;
  }
  return { embedded, referenced };
}

/** 마지막 메시지의 실행 하나를 사진 조회, 되묻지 않음, 원고의 사진 반영으로 판정한다. */
export function scoreRun(input: ScoreInput): RunScore {
  const { run, answers, events, status, failedTools, truncated, preview } = input;
  const notes = [...new Set(failedTools)].map((name) => `${name} 실패`);
  const environment: string[] = [];
  if (run.error) environment.push(`실행 오류: ${run.error}`);
  if (status !== "SUCCEEDED") environment.push(`실행 상태 ${status ?? "없음"}`);
  if (preview === null && failedTools.length > 0) environment.push("네이버 블로그 도구 실패");
  if (truncated) environment.push("실행 트리 잘림");
  if (environment.length > 0) return { outcome: "INCONCLUSIVE", reasons: environment, notes, photos: [] };

  const messageOf = new Map<string, number>();
  run.messages.forEach((message, index) => {
    for (const id of message.attachmentIds) messageOf.set(String(id), index);
  });
  // 대화 번호는 실행 입력을 만들 때의 사건으로만 찾는다. 조회 사건은 남의 대화 번호를 달고 올 수 있다
  const conversations = new Set(
    events
      .filter((event) => event.stage !== "inspected" && idsOf(event).some((id) => messageOf.has(id)))
      .map((event) => event.conversationId),
  );
  if (conversations.size === 0)
    return { outcome: "INCONCLUSIVE", reasons: ["로그에 대화 사건 없음"], notes, photos: [] };
  if (preview === null) return { outcome: "FAIL", reasons: ["미리보기 없음"], notes, photos: [] };

  const own = events.filter((event) => conversations.has(event.conversationId));
  const lastIndex = run.messages.length - 1;
  const { embedded, referenced } = lastMessageEvents(own, messageOf, lastIndex);
  const executions = new Set(input.executionIds);
  const inspectedOk = new Set(
    own
      .filter((event) => event.stage === "inspected" && executions.has(event.executionId) && event.outcome === "OK")
      .flatMap(idsOf),
  );
  const referencedIds = new Set(referenced ? idsOf(referenced) : []);
  const missingIds = new Set(embedded ? embedded.missing : []);
  const text = normalize(preview);

  const photos: PhotoResult[] = [];
  run.messages.forEach((message, index) => {
    message.attachmentIds.forEach((attachmentId, position) => {
      const id = String(attachmentId);
      const photoId = message.photoIds[position];
      const issues: PhotoIssue[] = [];
      if (index < lastIndex && !referencedIds.has(id)) issues.push("참조 누락");
      const mustInspect = index < lastIndex || missingIds.has(id);
      if (mustInspect && !inspectedOk.has(id)) issues.push("조회 누락");
      // 판독 실패는 조회했거나 이미지로 실은 사진에만 적는다. 보지 못한 사진은 조회 누락이 원인이다
      const answer = answers?.get(photoId);
      if (
        answer &&
        !issues.includes("조회 누락") &&
        !answer.accept.some((form) => text.includes(normalize(form)))
      )
        issues.push("판독 실패");
      if (!callsAttachment(preview, attachmentId)) issues.push("원고 누락");
      photos.push({ photoId, attachmentId, issues });
    });
  });
  const reasons = answers === null ? ["정답 없음"] : [];
  const outcome: RunOutcome = photos.some((photo) => photo.issues.length > 0) ? "FAIL" : "PASS";
  return { outcome, reasons, notes, photos };
}

/** 정한 횟수만큼 돌았고 모두 통과해야 시나리오가 통과다. */
export function scoreScenario(runs: RunOutcome[], repeat: number): RunOutcome {
  if (runs.includes("FAIL")) return "FAIL";
  if (runs.length === repeat && runs.every((outcome) => outcome === "PASS")) return "PASS";
  return "INCONCLUSIVE";
}

/** 모두 통과면 0, `FAIL` 이 있으면 1, 그 밖에는 2 다. */
export function exitCode(scenarioOutcomes: RunOutcome[]): number {
  if (scenarioOutcomes.includes("FAIL")) return 1;
  if (scenarioOutcomes.every((outcome) => outcome === "PASS")) return 0;
  return 2;
}
