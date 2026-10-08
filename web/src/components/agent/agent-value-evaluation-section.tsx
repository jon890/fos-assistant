"use client";

import { useState } from "react";
import { describeError, describeFailure } from "@/components/error-message";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatWhen } from "@/lib/format";
import {
  AXIS_LABELS,
  LEVEL_LABELS,
  orderCandidates,
  runValueEvaluation,
  type AutonomyDecisionView,
  type CandidateJudgement,
  type EvaluationCandidate,
  type EvaluationOverview,
} from "@/lib/value-evaluation";

type Props = {
  initialOverview: EvaluationOverview;
};

/**
 * 관리자 영역 에이전트 상세에서 내가 연 마지막 살펴보기를 평가하고 결과를 보이는 절이다.
 *
 * <p>단추는 판정까지 부르므로 자동 실행 설정과 동의가 켜져 있으면 읽기 전용 살펴보기가 시작될 수 있다.
 * 평가 결과를 고치거나 승인하는 동작과 자동 실행 동의를 바꾸는 칸은 없다. 모델이 쓴 설명은 평문으로 그린다.
 * 판정 묶음이 바뀌면 누른 응답으로 절 상태를 통째로 바꾼다.
 */
export function AgentValueEvaluationSection({ initialOverview }: Props) {
  const [overview, setOverview] = useState(initialOverview);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { check, evaluation, decisions } = overview;

  async function run(checkId: number) {
    setRunning(true);
    setError(null);
    try {
      const response = await runValueEvaluation(checkId);
      if (response.ok) {
        setOverview((await response.json()) as EvaluationOverview);
      } else {
        setError(await describeFailure(response));
      }
    } catch {
      setError(describeError("INTERNAL_ERROR", "요청을 처리하지 못했어요."));
    }
    setRunning(false);
  }

  return (
    <section
      aria-label="가치 평가"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">가치 평가</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        내가 연 마지막 살펴보기에서 받아들인 문제 후보를 평가하고, 행동 정책
        판정을 보여 드려요. 자동 실행 설정과 내 동의가 모두 켜져 있으면 판정에
        따라 읽기 전용 살펴보기가 한 번 시작될 수 있어요.
      </p>
      {check === null ? (
        <p className="mt-4 text-sm">
          받아들인 문제 후보가 있는 살펴보기가 없어요.
        </p>
      ) : (
        <>
          <p className="mt-4 text-sm">
            {check.finishedAt ? `${formatWhen(check.finishedAt)} 끝난 ` : ""}
            살펴보기에서 받아들인 문제 후보 {check.acceptedCandidates}개
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <Button
              disabled={evaluation?.outcome === "RUNNING"}
              loading={running}
              loadingText="평가하는 중이에요"
              onClick={() => void run(check.id)}
            >
              이 살펴보기 평가하기
            </Button>
          </div>
          {error ? (
            <Notice variant="error" role="alert" className="mt-3">
              {error}
            </Notice>
          ) : null}
          {evaluation ? (
            <div className="mt-4 flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="secondary">{evaluation.outcome}</Badge>
                  {evaluation.failure ? (
                    <Badge variant="destructive">{evaluation.failure}</Badge>
                  ) : null}
                </div>
                {evaluation.explanation ? (
                  <p className="text-sm break-words whitespace-pre-wrap">
                    {evaluation.explanation}
                  </p>
                ) : null}
              </div>
              <ul className="flex flex-col gap-3">
                {orderCandidates(evaluation).map((candidate) => (
                  <li key={candidate.id}>
                    <CandidateCard
                      candidate={candidate}
                      judgement={evaluation.judgements.find(
                        (item) => item.candidateId === candidate.id,
                      )}
                      decision={decisions.find(
                        (item) => item.candidateId === candidate.id,
                      )}
                    />
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}

function CandidateCard({
  candidate,
  judgement,
  decision,
}: {
  candidate: EvaluationCandidate;
  judgement: CandidateJudgement | undefined;
  decision: AutonomyDecisionView | undefined;
}) {
  return (
    <div className="rounded-md border border-border p-3">
      <p className="text-sm font-medium break-words">{candidate.problem}</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <Badge variant="outline">{candidate.actionType}</Badge>
        <Badge variant="outline">부작용 {candidate.sideEffect}</Badge>
      </div>
      {judgement ? (
        <div className="mt-3">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>축</TableHead>
                <TableHead>선택</TableHead>
                <TableHead>확신</TableHead>
                <TableHead>설명</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {judgement.axes.map((axis) => (
                <TableRow key={axis.axis}>
                  <TableCell className="align-top">
                    {AXIS_LABELS[axis.axis] ?? axis.axis}
                  </TableCell>
                  <TableCell className="align-top">
                    {LEVEL_LABELS[axis.choice] ?? axis.choice}
                  </TableCell>
                  <TableCell className="align-top">
                    {LEVEL_LABELS[axis.confidence] ?? axis.confidence}
                  </TableCell>
                  <TableCell className="align-top break-words whitespace-pre-wrap">
                    {axis.explanation}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="mt-2 text-sm text-muted-foreground">
            종합 확신{" "}
            {LEVEL_LABELS[judgement.confidence] ?? judgement.confidence}
          </p>
          {judgement.explanation ? (
            <p className="mt-1 text-sm break-words whitespace-pre-wrap">
              {judgement.explanation}
            </p>
          ) : null}
        </div>
      ) : (
        <p className="mt-3 text-sm text-muted-foreground">
          이 후보의 판단이 없어요.
        </p>
      )}
      {decision ? (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Badge variant="secondary">{decision.level}</Badge>
          {decision.reasons.map((reason) => (
            <Badge key={reason} variant="outline">
              {reason}
            </Badge>
          ))}
          {decision.executionStatus ? (
            <Badge variant="info">{decision.executionStatus}</Badge>
          ) : null}
        </div>
      ) : (
        <p className="mt-3 text-sm text-muted-foreground">판정이 없어요.</p>
      )}
    </div>
  );
}
