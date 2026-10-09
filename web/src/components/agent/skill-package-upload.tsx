"use client";

import { useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Notice } from "@/components/ui/notice";
import { describeError, describeFailure } from "@/components/error-message";
import { previewSkillPackage, uploadSkillPackage } from "@/lib/agent-api";
import type {
  SkillPackageChange,
  SkillPackageFileView,
  SkillPackagePreviewView,
  SkillPackageReason,
} from "@/lib/skill";
import { formatSize } from "@/lib/workspace-file";

const SANDBOX_MESSAGE =
  "이 에이전트는 실행 공간이 없어 스크립트가 든 스킬을 올릴 수 없어요. 도구에서 셸을 켜 주세요.";

/** 미리보기 문제의 까닭마다 보일 문구다. 백엔드에 까닭이 늘면 타입이 빠진 값을 잡는다. */
const REASON_MESSAGES: Record<SkillPackageReason, string> = {
  NOT_ZIP: "zip 파일이 아니에요.",
  ZIP_TOO_LARGE: "zip 파일이 너무 커요.",
  TOO_MANY_ENTRIES: "zip 안에 든 항목이 너무 많아요.",
  UNPACKED_TOO_LARGE: "압축을 풀면 너무 커요.",
  UNSAFE_ENTRY:
    "받을 수 없는 항목이 있어요. 링크, 암호, 깨진 파일이 없는지 확인해 주세요.",
  NO_SKILL_MD: "zip 맨 위에 SKILL.md 가 없어요.",
  PATH_NOT_ALLOWED: "스킬에 둘 수 없는 파일 경로예요.",
  NESTED_SKILL_MD: "SKILL.md 는 맨 위에만 둘 수 있어요.",
  NOT_TEXT: "글 파일이 아니에요. 그림 같은 파일은 올릴 수 없어요.",
  FILE_TOO_LARGE: "파일 하나가 너무 커요.",
  TOO_MANY_FILES: "파일이 너무 많아요.",
  TOTAL_TOO_LARGE: "파일 크기를 합치면 너무 커요.",
  SECRET_VALUE: "비밀값처럼 보이는 글이 있어요.",
  FRONTMATTER_INVALID: "SKILL.md 앞머리를 읽지 못했어요.",
  NAME_INVALID:
    "앞머리의 name 은 소문자, 숫자, 하이픈으로 64자까지 쓸 수 있어요.",
  NO_BODY: "SKILL.md 앞머리 아래에 스킬 본문을 적어 주세요.",
  SECRET_REQUEST: "앞머리에 환경 값이나 파일을 요청하는 칸이 있어요.",
  DESCRIPTION_TOO_LONG: "설명이 너무 길어요. 새 스킬은 60자까지예요.",
  NAME_TAKEN: "같은 이름의 기본 스킬이 있어요.",
  LIMIT_REACHED: "올릴 수 있는 스킬 수를 다 썼어요.",
  SCRIPTS_NEED_SANDBOX: SANDBOX_MESSAGE,
};

const CHANGE_LABELS: Record<
  SkillPackageChange,
  { label: string; variant: "success" | "warning" | "outline" | "destructive" }
> = {
  ADDED: { label: "새 파일", variant: "success" },
  CHANGED: { label: "바뀜", variant: "warning" },
  SAME: { label: "같음", variant: "outline" },
  REMOVED: { label: "지워짐", variant: "destructive" },
};

/** 미리보기와 올리기에서만 뜻이 정해지는 오류 코드의 문구다. 나머지는 공용 문구를 쓴다. */
const UPLOAD_FAILURES: Record<string, string> = {
  FORBIDDEN: "이 에이전트의 스킬을 관리할 수 없어요.",
  SKILL_CHANGED: "미리본 뒤에 스킬이 바뀌었어요. 파일을 다시 골라 주세요.",
  SKILL_SCRIPTS_NEED_SANDBOX: SANDBOX_MESSAGE,
  SKILL_PACKAGE_INVALID: "이 zip 은 올릴 수 없어요. 파일을 다시 골라 주세요.",
};

const UNREACHABLE = describeError("HERMES_UNAVAILABLE", "연결할 수 없어요.");

/** 덮어쓰기면 바뀌는 파일을 위에 둔다. 새 스킬이면 서버가 준 순서 그대로다. */
function orderFiles(preview: SkillPackagePreviewView): SkillPackageFileView[] {
  if (!preview.existing) return preview.files;
  return [
    ...preview.files.filter((file) => file.change !== "SAME"),
    ...preview.files.filter((file) => file.change === "SAME"),
  ];
}

function titleOf(preview: SkillPackagePreviewView | null): string {
  if (!preview?.name) return "스킬 묶음을 올릴 수 없어요";
  return preview.existing
    ? `${preview.name} 스킬을 덮어쓸까요?`
    : `${preview.name} 스킬을 올릴까요?`;
}

/** 미리보기의 문제, 설명, 파일 목록, 뺀 파일, `SKILL.md` 앞부분이다. */
function PreviewBody({ preview }: { preview: SkillPackagePreviewView }) {
  const problems = preview.problems;
  const sandboxBlocked = problems.some(
    (problem) => problem.reason === "SCRIPTS_NEED_SANDBOX",
  );
  return (
    <>
      {problems.length > 0 ? (
        <Notice variant="error">
          <ul className="grid gap-1">
            {problems.map((problem, index) => (
              <li key={`${problem.reason}-${problem.path ?? ""}-${index}`}>
                {REASON_MESSAGES[problem.reason] ??
                  "올릴 수 없는 내용이 있어요."}
                {problem.path ? (
                  <span className="ml-1 font-mono text-xs break-all">
                    {problem.path}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        </Notice>
      ) : null}
      {preview.description ? (
        <p className="text-sm break-words">{preview.description}</p>
      ) : null}
      {preview.hasScripts && !sandboxBlocked ? (
        <Notice variant="info">
          이 스크립트는 실행 공간에서 에이전트가 실행할 수 있어요.
        </Notice>
      ) : null}
      {preview.files.length > 0 ? (
        <section aria-label="묶음의 파일">
          <h3 className="text-xs font-medium">파일</h3>
          <ul className="mt-1 divide-y divide-border rounded-md border border-border">
            {orderFiles(preview).map((entry) => (
              <li
                key={entry.path}
                className="flex items-center justify-between gap-2 px-2 py-1.5"
              >
                <span className="min-w-0 font-mono text-xs break-all">
                  {entry.path}
                </span>
                <span className="flex shrink-0 items-center gap-2">
                  <span className="text-xs text-muted-foreground">
                    {formatSize(entry.size)}
                  </span>
                  <Badge variant={CHANGE_LABELS[entry.change].variant}>
                    {CHANGE_LABELS[entry.change].label}
                  </Badge>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {preview.ignored.length > 0 ? (
        <section aria-label="빼고 올리는 파일">
          <h3 className="text-xs font-medium">빼고 올리는 파일</h3>
          <ul className="mt-1 grid gap-0.5">
            {preview.ignored.map((path) => (
              <li
                key={path}
                className="font-mono text-xs break-all text-muted-foreground"
              >
                {path}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {preview.skillMdHead ? (
        <section aria-label="SKILL.md 앞부분">
          <h3 className="text-xs font-medium">SKILL.md 앞부분</h3>
          {/* 묶음의 글은 신뢰하지 않으므로 마크다운으로 그리지 않고 글 그대로 보인다. */}
          <pre className="mt-1 max-h-48 overflow-auto rounded-md bg-muted p-2 font-mono text-xs break-words whitespace-pre-wrap">
            {preview.skillMdHead}
          </pre>
        </section>
      ) : null}
    </>
  );
}

/**
 * 스킬 zip 묶음을 골라 미리보고 올린다.
 *
 * <p>같은 `File` 을 미리보기와 올리기에 한 번씩 보낸다. 서버는 그 사이에 아무것도 남기지 않는다.
 * 올리는 동안 창이 닫히지 않고, 실패하면 창이 남아 까닭을 보인다.
 */
export function SkillPackageUpload({
  code,
  onUploaded,
}: {
  code: string;
  onUploaded(): Promise<void>;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<SkillPackagePreviewView | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    if (uploading) return;
    setFile(null);
    setPreview(null);
    setError(null);
  }

  async function choose(chosen: File) {
    setPreviewing(true);
    setPreview(null);
    setError(null);
    try {
      const response = await previewSkillPackage(code, chosen);
      if (response.ok) {
        setPreview((await response.json()) as SkillPackagePreviewView);
      } else {
        setError(await describeFailure(response, UPLOAD_FAILURES));
      }
    } catch {
      setError(UNREACHABLE);
    } finally {
      setFile(chosen);
      setPreviewing(false);
    }
  }

  async function upload() {
    if (!file || !preview) return;
    setUploading(true);
    setError(null);
    try {
      const response = await uploadSkillPackage(code, file, preview.baseDigest);
      if (!response.ok) {
        setError(await describeFailure(response, UPLOAD_FAILURES));
        return;
      }
      setFile(null);
      setPreview(null);
      await onUploaded();
    } catch {
      setError(UNREACHABLE);
    } finally {
      setUploading(false);
    }
  }

  const problems = preview?.problems ?? [];
  const uploadable = preview !== null && problems.length === 0;
  const actionLabel = preview?.existing ? "덮어쓰기" : "올리기";

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept=".zip,application/zip"
        aria-label="스킬 zip 파일"
        className="hidden"
        onChange={(event) => {
          const chosen = event.target.files?.[0];
          // 같은 파일을 다시 골라도 change 가 나도록 값을 비운다.
          event.target.value = "";
          if (chosen) void choose(chosen);
        }}
      />
      <Button
        size="sm"
        variant="outline"
        loading={previewing}
        loadingText="읽는 중"
        onClick={() => inputRef.current?.click()}
      >
        zip 으로 올리기
      </Button>
      <Dialog
        open={file !== null}
        onOpenChange={(open) => {
          if (!open) close();
        }}
      >
        <DialogContent
          showCloseButton={!uploading}
          className="max-h-[85dvh] overflow-y-auto sm:max-w-lg"
        >
          <DialogHeader>
            <DialogTitle className="break-all">{titleOf(preview)}</DialogTitle>
            <DialogDescription>
              {preview === null
                ? "아래 까닭을 확인해 주세요."
                : uploadable
                  ? preview.existing
                    ? "덮어쓰면 지금 스킬이 아래 파일로 바뀌어요. 다음 실행부터 반영돼요."
                    : "올리면 다음 실행부터 이 에이전트가 이 스킬을 쓸 수 있어요."
                  : "아래 내용을 고친 zip 을 다시 골라 주세요."}
            </DialogDescription>
          </DialogHeader>
          {preview ? <PreviewBody preview={preview} /> : null}
          {error ? (
            <Notice variant="error" role="alert">
              {error}
            </Notice>
          ) : null}
          <DialogFooter>
            <Button variant="outline" disabled={uploading} onClick={close}>
              취소
            </Button>
            {preview ? (
              <Button
                disabled={!uploadable}
                loading={uploading}
                loadingText={preview.existing ? "덮어쓰는 중" : "올리는 중"}
                onClick={() => void upload()}
              >
                {actionLabel}
              </Button>
            ) : null}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
