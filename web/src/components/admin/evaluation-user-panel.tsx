"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { describeAdminError } from "@/components/error-message";
import {
  MISSING_LABELS,
  prepareEvaluationUser,
  type EvaluationUserStatus,
} from "@/lib/quality-api";

/**
 * 평가 전용 사용자를 준비하는 칸이다. 사용자가 없으면 profile 이름을 받아 처음 만들고, 빠진 것이 있으면 같은 profile 로 다시 채운다.
 *
 * <p>profile 은 운영이 실행 공간과 사진 정책에 먼저 등록해 둔 이름이다(docs/features/agent-quality/design.md 의 「운영 저장소에 넘길 요청」).
 */
export function EvaluationUserPanel({
  status,
  onPrepared,
}: {
  status: EvaluationUserStatus;
  onPrepared(next: EvaluationUserStatus): void;
}) {
  const [profile, setProfile] = useState(status.hermesProfile ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const missingUser = status.missing.includes("USER");

  async function prepare() {
    setBusy(true);
    setError(null);
    try {
      const response = await prepareEvaluationUser(profile);
      const payload = (await response.json().catch(() => ({}))) as
        EvaluationUserStatus | { code?: string; message?: string };
      if (response.ok) {
        onPrepared(payload as EvaluationUserStatus);
      } else {
        const failure = payload as { code?: string; message?: string };
        setError(
          describeAdminError(
            failure.code ?? "INTERNAL_ERROR",
            failure.message ?? "요청을 처리하지 못했어요.",
          ),
        );
      }
    } catch {
      setError(
        describeAdminError("INTERNAL_ERROR", "요청을 처리하지 못했어요."),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section
      aria-label="평가 사용자"
      data-testid="evaluation-user"
      className="space-y-3 rounded-md border border-border p-4 text-sm"
    >
      {missingUser ? (
        <p>
          배포 뒤 확인은 평가 전용 사용자로 돌아요. 먼저 평가 사용자를 준비해
          주세요.
        </p>
      ) : (
        <Notice variant="warning">
          <p>평가 사용자 준비가 덜 끝났어요.</p>
          <ul className="mt-1 list-disc pl-5">
            {status.missing.map((code) => (
              <li key={code}>{MISSING_LABELS[code] ?? code}</li>
            ))}
          </ul>
        </Notice>
      )}
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-48 flex-1 space-y-1">
          <Label htmlFor="evaluation-profile">Hermes profile</Label>
          <Input
            id="evaluation-profile"
            value={profile}
            disabled={!missingUser || busy}
            onChange={(event) => setProfile(event.target.value)}
            placeholder="실행 공간에 등록한 profile 이름"
          />
        </div>
        <Button
          disabled={busy || profile.trim().length === 0}
          loading={busy}
          loadingText="준비하는 중…"
          onClick={() => void prepare()}
        >
          {missingUser ? "평가 사용자 준비" : "다시 준비"}
        </Button>
      </div>
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
    </section>
  );
}
