/** 관리자 영역 가치 평가 절이 읽는 묶음이다. 모양은 Control Plane 의 `EvaluationOverviewResponse` 와 같다. */
export type EvaluationAxis =
  | "GOAL_ALIGNMENT"
  | "URGENCY"
  | "EXPECTED_BENEFIT"
  | "COST"
  | "RISK"
  | "EVIDENCE_QUALITY";

/** 축의 선택이다. */
export type EvaluationLevel = "LOW" | "MEDIUM" | "HIGH" | "UNKNOWN";

/** 판단의 확신이다. 선택과 달리 `UNKNOWN` 이 없다. */
export type EvaluationConfidence = "LOW" | "MEDIUM" | "HIGH";

export type AxisJudgement = {
  axis: EvaluationAxis;
  choice: EvaluationLevel;
  confidence: EvaluationConfidence;
  explanation: string;
  evidenceKeys: string[];
};

export type CandidateJudgement = {
  candidateId: number;
  axes: AxisJudgement[];
  confidence: EvaluationConfidence;
  explanation: string;
};

export type EvaluationCandidate = {
  id: number;
  problemKey: string;
  problem: string;
  actionType: string;
  sideEffect: string;
};

export type ValueEvaluationView = {
  id: number;
  replayOfId: number | null;
  outcome: string;
  /** `FALLBACK` 일 때만 있다. */
  failure: string | null;
  candidates: EvaluationCandidate[];
  judgements: CandidateJudgement[];
  orderedCandidateIds: number[];
  explanation: string | null;
};

export type AutonomyDecisionView = {
  id: number;
  candidateId: number;
  level: string;
  reasons: string[];
  /** `EXECUTE` 가 아니면 null 이다. */
  executionStatus: string | null;
};

export type EvaluationCheckSummary = {
  id: number;
  trigger: string;
  finishedAt: string | null;
  acceptedCandidates: number;
};

/** 고를 살펴보기가 없으면 `check` 가 null 이고, 아직 평가하지 않았으면 `evaluation` 이 null 이다. */
export type EvaluationOverview = {
  check: EvaluationCheckSummary | null;
  evaluation: ValueEvaluationView | null;
  decisions: AutonomyDecisionView[];
};

export const AXIS_LABELS: Record<EvaluationAxis, string> = {
  GOAL_ALIGNMENT: "목표와 맞음",
  URGENCY: "시급함",
  EXPECTED_BENEFIT: "기대 효과",
  COST: "비용",
  RISK: "위험",
  EVIDENCE_QUALITY: "근거의 질",
};

export const LEVEL_LABELS: Record<
  EvaluationLevel | EvaluationConfidence,
  string
> = {
  LOW: "낮음",
  MEDIUM: "중간",
  HIGH: "높음",
  UNKNOWN: "모름",
};

/** 평가하고 판정한다. 본문은 보내지 않는다. 판단 provider 는 서버 라우트가 정한다. */
export function runValueEvaluation(checkId: number): Promise<Response> {
  return fetch(`/api/admin/proactive-checks/${checkId}/value-evaluation-runs`, {
    method: "POST",
  });
}

/**
 * 후보를 그릴 순서를 정한다. 추천 순서에 든 후보를 그 순서대로 두고, 없는 후보는 식별자 오름차순으로 뒤에 붙인다.
 * 추천 순서에 있지만 후보에 없는 식별자는 건너뛴다.
 */
export function orderCandidates(
  evaluation: ValueEvaluationView,
): EvaluationCandidate[] {
  const byId = new Map(
    evaluation.candidates.map((candidate) => [candidate.id, candidate]),
  );
  const ordered = evaluation.orderedCandidateIds.flatMap((id) => {
    const candidate = byId.get(id);
    byId.delete(id);
    return candidate ? [candidate] : [];
  });
  const rest = [...byId.values()].sort((a, b) => a.id - b.id);
  return [...ordered, ...rest];
}
