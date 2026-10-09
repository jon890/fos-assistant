"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { WorkspaceEntryList } from "@/components/workspace/workspace-entry-list";
import { WorkspacePreview } from "@/components/workspace/workspace-preview";
import {
  fetchWorkspaceEntries,
  fetchWorkspaceStatus,
  type WorkspaceListing,
  type WorkspaceStatus,
} from "@/lib/workspace-api";
import { crumbs, explorerHref, joinPath } from "@/lib/workspace-file";

/** 읽은 결과다. 실패하면 응답 상태를 둔다. 연결이 끊긴 실패는 0 이다. */
type Read<T> = { ok: true; data: T } | { ok: false; status: number };

async function readJson<T>(request: () => Promise<Response>): Promise<Read<T>> {
  try {
    const response = await request();
    if (!response.ok) return { ok: false, status: response.status };
    return { ok: true, data: (await response.json()) as T };
  } catch {
    return { ok: false, status: 0 };
  }
}

function Muted({ text }: { text: string }) {
  return <p className="text-sm text-muted-foreground">{text}</p>;
}

const EMPTY = <Muted text="아직 에이전트가 만든 파일이 없어요" />;
const LOADING = <Muted text="불러오는 중…" />;

/** 머리의 안내다. 이 공간을 함께 쓰는 에이전트와 그룹 공개 여부를 보인다. */
function SharedAgents({ status }: { status: WorkspaceStatus }) {
  return (
    <div className="space-y-1 text-sm text-muted-foreground">
      <p>내 에이전트들이 함께 쓰는 공간이에요</p>
      <ul aria-label="함께 쓰는 에이전트" className="flex flex-wrap gap-2">
        {status.agents.map((agent) => (
          <li key={agent.code} className="flex gap-1 text-foreground">
            {agent.name}
            {agent.shared ? <Badge variant="info">그룹 공개</Badge> : null}
          </li>
        ))}
      </ul>
      {status.agents.some((agent) => agent.shared) ? (
        <p>다른 사람이 이 에이전트를 쓰면 그 파일도 여기 생겨요</p>
      ) : null}
    </div>
  );
}

/** 「파일 공간」 부터 연 디렉터리까지의 경로 줄이다. 마지막 조각은 지금 연 곳이라 링크가 아니다. */
function Crumbs({ path }: { path: string }) {
  const parts = crumbs(path);
  return (
    <nav aria-label="경로">
      <ol className="flex flex-wrap gap-1 text-sm text-muted-foreground">
        {parts.map((part, index) => (
          <li key={part.path} className="flex gap-1 break-all">
            {index > 0 ? <span aria-hidden="true">/</span> : null}
            {index === parts.length - 1 ? (
              <span aria-current="page" className="font-medium text-foreground">
                {part.name}
              </span>
            ) : (
              <Link
                href={explorerHref(part.path)}
                prefetch={false}
                className="hover:text-foreground hover:underline"
              >
                {part.name}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** 읽지 못했을 때의 안내다. 목록의 경로가 틀렸거나(400) 없으면(404) 맨 위로, 그 밖은 다시 읽기를 준다. */
function Failure({ status, onRetry }: { status: number; onRetry(): void }) {
  const lost = status === 400 || status === 404;
  return (
    <Notice variant="error" role="alert">
      <p>
        {status === 400
          ? "경로가 올바르지 않아요."
          : status === 404
            ? "찾을 수 없어요. 지워졌을 수 있어요."
            : "불러오지 못했어요"}
      </p>
      {lost ? (
        <Link href="/files" prefetch={false} className="underline">
          맨 위로 가기
        </Link>
      ) : (
        <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
          다시 읽기
        </Button>
      )}
    </Notice>
  );
}

/**
 * 사용자 실행 공간의 목록과 미리보기다. 연 디렉터리와 미리 보는 파일은 주소의 `path`, `file` 이 정한다.
 *
 * <p>상태와 목록은 브라우저에서 읽는다. 실패하면 이 자리에서 다시 읽는다. 지우기는 그리지 않는다.
 */
export function WorkspaceExplorer() {
  const params = useSearchParams();
  const router = useRouter();
  const path = params.get("path") ?? "";
  const file = params.get("file");
  const wide = useMediaQuery("(min-width: 1024px)");
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<Read<WorkspaceStatus> | null>(null);
  // `path` 는 이 목록이 어느 디렉터리의 것인지다. 주소가 바뀌면 새 목록이 올 때까지 불러오는 중으로 그린다.
  const [listing, setListing] = useState<{
    path: string;
    read: Read<WorkspaceListing>;
  } | null>(null);

  useEffect(() => {
    let cancelled = false;
    void readJson<WorkspaceStatus>(fetchWorkspaceStatus).then((read) => {
      if (!cancelled) setStatus(read);
    });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const ready =
    status?.ok === true && status.data.available && status.data.exists;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    void readJson<WorkspaceListing>(() => fetchWorkspaceEntries(path)).then(
      (read) => {
        if (!cancelled) setListing({ path, read });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [ready, attempt, path]);

  const retry = () => {
    setStatus(null);
    setListing(null);
    setAttempt((value) => value + 1);
  };

  if (status === null) return LOADING;
  if (!status.ok) return <Failure status={0} onRetry={retry} />;
  if (!status.data.available) {
    return (
      <Notice variant="error" role="alert">
        파일 공간을 쓸 수 없어요. 관리자에게 알려 주세요.
      </Notice>
    );
  }

  const read = listing?.path === path ? listing.read : null;
  let content = EMPTY;
  let preview = null;
  if (status.data.exists && read === null) {
    content = LOADING;
  } else if (read !== null && !read.ok) {
    content = <Failure status={read.status} onRetry={retry} />;
  } else if (read !== null && read.data.entries.length > 0) {
    const { entries, truncated } = read.data;
    const selected = entries.find(
      (entry) =>
        entry.kind === "FILE" &&
        entry.readable &&
        entry.openable &&
        joinPath(path, entry.name) === file,
    );
    content = (
      <WorkspaceEntryList
        path={path}
        entries={entries}
        truncated={truncated}
        selected={selected === undefined ? null : file}
      />
    );
    if (selected !== undefined && file !== null) {
      preview = (
        <WorkspacePreview
          path={file}
          name={selected.name}
          size={selected.size}
          wide={wide}
          onClose={() => router.push(explorerHref(path), { scroll: false })}
        />
      );
    }
  }

  return (
    <div className="space-y-4">
      <SharedAgents status={status.data} />
      <div className="flex min-w-0 items-start gap-4">
        <div className="min-w-0 flex-1 space-y-3">
          <Crumbs path={path} />
          {content}
        </div>
        {preview}
      </div>
    </div>
  );
}
