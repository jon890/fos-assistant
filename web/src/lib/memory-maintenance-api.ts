/**
 * 정비 후보다. `GET /api/v1/memory/maintenance-candidates` 의 한 줄을 그대로 받는다.
 *
 * <p>`id` 는 후보 사건 번호이고 기억 번호는 `memoryId` 다. 「맞음」 을 눌러도 기억은 바뀌지 않고 확인 결과만 남는다.
 */
export type MaintenanceCandidate = {
  id: number;
  memoryId: number;
  scope: "USER" | "GROUP";
  title: string;
  action: "ARCHIVE" | "DEDUP";
  summary: string;
  archivable: boolean;
  proposedAt: string;
};

export type MaintenanceReview = "CORRECT" | "INCORRECT";

/** 화면에 보이는 동작 이름이다. 모르는 값이 오면 값을 그대로 보인다. */
export const MAINTENANCE_ACTION_LABELS: Record<
  MaintenanceCandidate["action"],
  string
> = {
  ARCHIVE: "보관",
  DEDUP: "중복 정리",
};

export function fetchMaintenanceCandidates(): Promise<Response> {
  return fetch("/api/memory/maintenance-candidates", { cache: "no-store" });
}

export function reviewMaintenanceCandidate(
  id: number,
  review: MaintenanceReview,
): Promise<Response> {
  return fetch(`/api/memory/maintenance-candidates/${id}/review`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ review }),
  });
}
