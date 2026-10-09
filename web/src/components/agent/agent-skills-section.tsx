"use client";

import Link from "next/link";
import { useState } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { useAdminView } from "@/components/shell/app-shell";
import { describeError, describeFailure } from "@/components/error-message";
import {
  deleteSkill,
  fetchAgentSkills,
  setSkillEnabled,
} from "@/lib/agent-api";
import { formatWhen } from "@/lib/format";
import { SkillPackageUpload } from "./skill-package-upload";
import type { SkillItemView, SkillListView } from "@/lib/skill";

type Props = {
  code: string;
  initialSkills: SkillListView;
};

/** 이 절에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const SKILL_FAILURES: Record<string, string> = {
  FORBIDDEN: "이 에이전트의 스킬을 관리할 수 없어요.",
  HERMES_UNAVAILABLE: "반영하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
};

/** 지우기 확인 창이다. 요청이 도는 동안 닫히지 않고, 실패하면 창이 남아 까닭을 보인다. */
function DeleteConfirm({
  name,
  busy,
  error,
  onCancel,
  onConfirm,
}: {
  name: string;
  busy: boolean;
  error: string | null;
  onCancel(): void;
  onConfirm(): void;
}) {
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onCancel();
      }}
    >
      <AlertDialogContent
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>{name} 스킬을 지울까요?</AlertDialogTitle>
          <AlertDialogDescription>
            지우면 이 에이전트가 이 스킬과 참고 파일을 더 쓰지 못해요. 다음
            실행부터 반영돼요.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {error ? (
          <Notice variant="error" role="alert">
            {error}
          </Notice>
        ) : null}
        <AlertDialogFooter>
          {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy}>
              취소
            </Button>
          </AlertDialogCancel>
          {/* AlertDialogAction 은 누르는 즉시 창을 닫아, 지우기가 실패해도 창이 사라지므로 일반 Button 으로 둔다. */}
          <Button
            variant="destructive"
            loading={busy}
            loadingText="지우는 중"
            onClick={onConfirm}
          >
            지우기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function usageText(skill: SkillItemView): string | null {
  if (!skill.usage) return null;
  if (skill.usage.count === 0 || skill.usage.lastInvokedAt === null)
    return "아직 호출이 없어요";
  return `호출 ${skill.usage.count}번, 마지막 호출 ${formatWhen(skill.usage.lastInvokedAt)}`;
}

/** 에이전트가 쓸 수 있는 스킬을 보이고, 관리하는 사람은 켜고 끄고 올리고 지운다. */
export function AgentSkillsSection({ code, initialSkills }: Props) {
  const admin = useAdminView();
  const [list, setList] = useState(initialSkills);
  const [pendingName, setPendingName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const editable = list.editable;

  async function reload(): Promise<string | null> {
    try {
      const response = await fetchAgentSkills(code, admin);
      if (!response.ok) return await describeFailure(response, SKILL_FAILURES);
      setList((await response.json()) as SkillListView);
      return null;
    } catch {
      return describeError("HERMES_UNAVAILABLE", "연결할 수 없어요.");
    }
  }

  async function toggle(skill: SkillItemView) {
    setPendingName(skill.name);
    setError(null);
    try {
      const response = await setSkillEnabled(
        code,
        skill.name,
        !skill.enabled,
        admin,
      );
      setError(
        response.ok
          ? await reload()
          : await describeFailure(response, SKILL_FAILURES),
      );
    } catch {
      setError(SKILL_FAILURES.HERMES_UNAVAILABLE!);
    } finally {
      setPendingName(null);
    }
  }

  async function remove(name: string) {
    setPendingName(name);
    setDeleteError(null);
    try {
      const response = await deleteSkill(code, name);
      if (!response.ok) {
        setDeleteError(await describeFailure(response, SKILL_FAILURES));
        return;
      }
      setConfirming(null);
      setError(await reload());
    } catch {
      setDeleteError(SKILL_FAILURES.HERMES_UNAVAILABLE!);
    } finally {
      setPendingName(null);
    }
  }

  const busy = pendingName !== null;

  return (
    <section
      aria-label="스킬"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">스킬</h2>
          {editable ? (
            <p className="mt-1 text-sm text-muted-foreground">
              저장하면 다음 실행부터 반영돼요.
            </p>
          ) : (
            <p className="mt-1 text-sm text-muted-foreground">
              <code className="rounded-sm bg-muted px-1 py-0.5 font-mono text-[0.9em]">
                /이름
              </code>
              으로 부를 수 있어요.
            </p>
          )}
        </div>
        {editable ? (
          <div className="flex flex-wrap gap-2">
            <Button asChild size="sm" variant="outline">
              <Link prefetch={false} href={`/agents/${code}/skills/new`}>
                스킬 추가
              </Link>
            </Button>
            <SkillPackageUpload
              code={code}
              onUploaded={async () => setError(await reload())}
            />
          </div>
        ) : null}
      </div>
      {error ? (
        <Notice variant="error" role="alert" className="mt-4">
          {error}
        </Notice>
      ) : null}
      {list.skills.filter((skill) => admin || skill.source === "UPLOADED")
        .length === 0 ? (
        <Notice variant="info" className="mt-4">
          {editable && !list.skillsToolsetEnabled
            ? "스킬을 추가하면 스킬 도구가 함께 켜져요."
            : "아직 스킬이 없어요."}
        </Notice>
      ) : (
        <ul className="mt-4 divide-y divide-border rounded-md border border-border">
          {list.skills
            .filter((skill) => admin || skill.source === "UPLOADED")
            .map((skill) => {
              const usage = usageText(skill);
              const uploaded = skill.source === "UPLOADED";
              return (
                <li
                  key={skill.name}
                  className="flex items-center justify-between gap-3 p-3"
                >
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      {editable && uploaded ? (
                        <Link
                          prefetch={false}
                          href={`/agents/${code}/skills/${skill.name}`}
                          className="text-sm font-medium break-all underline underline-offset-4"
                          aria-label={`${skill.name} 내용 고치기`}
                        >
                          {skill.name}
                        </Link>
                      ) : (
                        <p className="text-sm font-medium break-all">
                          {skill.name}
                        </p>
                      )}
                      <Badge variant="outline">
                        {uploaded ? "올린 스킬" : "기본 스킬"}
                      </Badge>
                    </div>
                    {skill.description ? (
                      <p className="mt-1 text-xs text-muted-foreground">
                        {skill.description}
                      </p>
                    ) : null}
                    {usage ? (
                      <p className="mt-1 text-xs text-muted-foreground">
                        {usage}
                      </p>
                    ) : null}
                    {editable && uploaded ? (
                      <div className="mt-2 flex gap-2">
                        <Button asChild size="sm" variant="outline">
                          <Link
                            prefetch={false}
                            href={`/agents/${code}/skills/${skill.name}`}
                            aria-label={`${skill.name} 편집`}
                          >
                            내용 고치기
                          </Link>
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          aria-label={`${skill.name} 삭제`}
                          onClick={() => {
                            setDeleteError(null);
                            setConfirming(skill.name);
                          }}
                        >
                          삭제
                        </Button>
                      </div>
                    ) : null}
                  </div>
                  {editable ? (
                    <Switch
                      checked={skill.enabled}
                      loading={
                        pendingName === skill.name && confirming === null
                      }
                      disabled={busy}
                      aria-label={`${skill.name} 스킬`}
                      onCheckedChange={() => void toggle(skill)}
                    />
                  ) : (
                    <Badge variant={skill.enabled ? "success" : "outline"}>
                      {skill.enabled ? "켜짐" : "꺼짐"}
                    </Badge>
                  )}
                </li>
              );
            })}
        </ul>
      )}
      {confirming !== null ? (
        <DeleteConfirm
          name={confirming}
          busy={pendingName === confirming}
          error={deleteError}
          onCancel={() => {
            setConfirming(null);
            setDeleteError(null);
          }}
          onConfirm={() => void remove(confirming)}
        />
      ) : null}
    </section>
  );
}
