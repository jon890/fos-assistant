"use client";

import Link from "next/link";
import { FileText } from "lucide-react";
import { vaultContentUrl } from "@/lib/artifact-vault-api";
import { putFileKind, putFileName } from "@/lib/put-file";
import { formatSize } from "@/lib/workspace-file";
import type { MessageArtifact } from "@/components/chat/message-types";

/**
 * 비서가 이 답에서 「내 파일」 에 넣은 파일들이다(`kind: "FILE"`). 대화 폴더의 파일이 아니라 보관본이라 결과물 패널로 열지 않고,
 * 내려받기와 「내 파일에서 보기」 만 준다. 내 파일에서 지운 파일은 안내 한 줄만 보인다.
 */
export function PutFiles({ files }: { files: MessageArtifact[] }) {
  if (files.length === 0) return null;
  return (
    <ul className="mt-2 flex flex-col gap-1">
      {files.map((file, index) => (
        <li
          key={file.vaultItemId ?? `deleted-${index}`}
          data-testid="message-put-file"
          className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm"
        >
          <FileText aria-hidden="true" className="size-4 shrink-0" />
          {file.deleted || file.vaultItemId == null ? (
            <span className="text-muted-foreground">
              내 파일에서 지운 파일이에요
            </span>
          ) : (
            <>
              <span className="min-w-0 truncate font-medium" title={file.path}>
                {putFileName(file)}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {putFileKind(file.path)} · {formatSize(file.byteSize)}
              </span>
              <a
                href={vaultContentUrl(file.vaultItemId, true)}
                className="shrink-0 text-xs underline-offset-4 hover:underline"
              >
                내려받기
              </a>
              <Link
                href="/files?tab=vault"
                className="shrink-0 text-xs underline-offset-4 hover:underline"
              >
                내 파일에서 보기
              </Link>
            </>
          )}
        </li>
      ))}
    </ul>
  );
}
