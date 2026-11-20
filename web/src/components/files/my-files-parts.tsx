"use client";

import Link from "next/link";
import { X } from "lucide-react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import type { VaultItem, VaultKind, VaultList } from "@/lib/artifact-vault-api";
import { withParticle } from "@/lib/korean-particle";
import { KIND_LABELS, roundSize } from "@/lib/my-files";
import { formatSize } from "@/lib/workspace-file";

const KIND_CHOICES: { value: VaultKind | null; label: string }[] = [
  { value: null, label: "전체" },
  { value: "IMAGE", label: KIND_LABELS.IMAGE },
  { value: "DOCUMENT", label: KIND_LABELS.DOCUMENT },
  { value: "TABLE", label: KIND_LABELS.TABLE },
];

/** 쓴 용량과 전체 용량 한 줄이다. 가득 차면 새 파일이 들어오지 않는다는 안내를 붙인다(스펙 workspace REQ17). */
export function UsageLine({
  usage,
}: {
  usage: Omit<VaultList, "items" | "nextCursor">;
}) {
  return (
    <div className="space-y-2">
      <p data-testid="my-files-usage" className="text-sm text-muted-foreground">
        {formatSize(usage.usedBytes)} /{" "}
        {roundSize(formatSize(usage.quotaBytes))} 사용
      </p>
      {usage.full ? (
        <Notice variant="error" role="alert" data-testid="my-files-full">
          내 파일 용량이 가득 찼어요. 새 파일이 들어오지 않으니 안 쓰는 파일을
          지워 비워 주세요.
        </Notice>
      ) : null}
    </div>
  );
}

/** 목록 위의 검색 칸과 종류 고르기다. 휴대폰 폭에서도 목록 위에 둔다(스펙 workspace REQ11, REQ12, REQ18). */
export function FileFilters({
  text,
  kind,
  onText,
  onKind,
}: {
  text: string;
  kind: VaultKind | null;
  onText(value: string): void;
  onKind(value: VaultKind | null): void;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Input
        type="search"
        value={text}
        onChange={(event) => onText(event.target.value)}
        placeholder="제목, 설명, 파일 이름, 대화 제목으로 찾기"
        aria-label="내 파일 찾기"
        maxLength={100}
        className="min-w-48 flex-1 sm:max-w-sm"
      />
      <div role="group" aria-label="종류" className="flex shrink-0 gap-1">
        {KIND_CHOICES.map((choice) => (
          <Button
            key={choice.label}
            variant={kind === choice.value ? "default" : "outline"}
            size="sm"
            aria-pressed={kind === choice.value}
            onClick={() => onKind(choice.value)}
          >
            {choice.label}
          </Button>
        ))}
      </div>
    </div>
  );
}

/** 대화 필터 표시다. 「대화 열기」 로 그 대화로 가고 ✕ 로 필터를 푼다(스펙 workspace REQ13). */
export function ConversationFilter({
  id,
  title,
  onClear,
}: {
  id: string;
  title: string;
  onClear(): void;
}) {
  return (
    <div
      data-testid="my-files-conversation-filter"
      className="flex min-w-0 flex-wrap items-center gap-2 rounded-md border border-border bg-muted px-3 py-2 text-sm"
    >
      <span className="min-w-0 flex-1 truncate">
        「{title}」 에서 나온 파일
      </span>
      <Link
        href={`/chat/${id}`}
        prefetch={false}
        className="shrink-0 underline underline-offset-4"
      >
        대화 열기
      </Link>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label="모든 대화의 파일 보기"
        onClick={onClear}
      >
        <X aria-hidden />
      </Button>
    </div>
  );
}

/** 파일 하나를 지우는 확인 창이다. 지우는 동안에는 닫히지 않는다(스펙 workspace REQ15). */
export function DeleteFileDialog({
  name,
  busy,
  onCancel,
  onConfirm,
}: {
  name: string;
  busy: boolean;
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
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle className="break-all">
            {withParticle(name, "을", "를")} 지울까요?
          </AlertDialogTitle>
          <AlertDialogDescription>
            내 파일에서 지우면 다시 열 수 없어요. 대화와 에이전트 작업 폴더의
            원본은 그대로예요.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>취소</AlertDialogCancel>
          <Button
            variant="destructive"
            loading={busy}
            loadingText="지우는 중…"
            onClick={onConfirm}
          >
            지우기
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * 목록 자리의 안내다. 불러오지 못했으면 오류와 「다시 읽기」, 필터 없이 비었으면 파일이 모이는 곳이라는 안내, 필터가 있는데 비었으면
 * 찾은 파일이 없다는 안내다(스펙 workspace REQ18).
 */
export function ListState({
  failure,
  items,
  filtered,
  onRetry,
}: {
  failure: string | null;
  items: VaultItem[] | null;
  filtered: boolean;
  onRetry(): void;
}) {
  if (failure)
    return (
      <Notice variant="error" role="alert" data-testid="my-files-failure">
        <p>{failure}</p>
        <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
          다시 읽기
        </Button>
      </Notice>
    );
  if (items === null)
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  if (items.length > 0) return null;
  return filtered ? (
    <EmptyState
      title="찾은 파일이 없어요"
      description="다른 낱말로 찾거나 종류를 「전체」 로 바꿔 보세요."
    />
  ) : (
    <EmptyState
      title="아직 내 파일이 없어요"
      description="대화에서 에이전트가 만든 파일이 여기 모여요. 에이전트에게 파일 공간에 넣어 달라고 해도 들어와요."
    />
  );
}
