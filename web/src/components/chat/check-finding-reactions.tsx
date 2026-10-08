"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  reactToFinding,
  type CheckFinding,
  type FindingReaction,
} from "@/lib/check-finding-api";

const REACTIONS: { reaction: FindingReaction; label: string }[] = [
  { reaction: "ACCEPTED", label: "받아들임" },
  { reaction: "POSTPONED", label: "나중에" },
  { reaction: "DISMISSED", label: "관심 없음" },
];

/**
 * 발견 한 줄이다. 모델이 쓴 제목이라 평문으로만 그린다(ADR-009).
 *
 * <p>지금 반응의 단추는 눌린 상태로 그린다. 누르는 동안 이 줄의 단추를 끄고, 실패하면 이 줄 아래에 알린다.
 */
function FindingRow({
  finding,
  onChanged,
}: {
  finding: CheckFinding;
  onChanged(): void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function react(reaction: FindingReaction) {
    setBusy(true);
    setError(null);
    const result = await reactToFinding(finding.id, reaction);
    setBusy(false);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    onChanged();
  }

  return (
    <div
      role="group"
      aria-label={finding.title}
      data-testid="check-finding"
      data-reaction={finding.reaction ?? ""}
      className="text-sm"
    >
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="min-w-0 break-words">{finding.title}</span>
        <div className="flex flex-wrap gap-2">
          {REACTIONS.map(({ reaction, label }) => {
            const pressed = finding.reaction === reaction;
            return (
              <Button
                key={reaction}
                size="xs"
                variant={pressed ? "default" : "outline"}
                aria-pressed={pressed}
                disabled={busy}
                onClick={() => void react(reaction)}
              >
                {label}
              </Button>
            );
          })}
        </div>
      </div>
      {error ? (
        <p role="alert" className="mt-1 text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** 살펴보기 답 아래에 그 답의 「새로 알릴 것」 발견과 반응 단추를 보인다. 발견이 없으면 아무것도 그리지 않는다. */
export function CheckFindingReactions({
  findings,
  dismissWindowDays,
  onChanged,
}: {
  findings: CheckFinding[];
  dismissWindowDays: number;
  onChanged(): void;
}) {
  if (findings.length === 0) return null;
  return (
    <li
      data-testid="check-findings"
      aria-label="발견 반응"
      className="-mt-4 flex flex-col gap-2 pl-10"
    >
      {findings.map((finding) => (
        <FindingRow key={finding.id} finding={finding} onChanged={onChanged} />
      ))}
      <p className="text-xs text-muted-foreground">
        관심 없음을 고른 주제는 그 발견을 알린 날부터 {dismissWindowDays}일 동안
        다시 알리지 않아요.
      </p>
    </li>
  );
}
