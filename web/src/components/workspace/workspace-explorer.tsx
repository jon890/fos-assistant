"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { useMediaQuery } from "@/components/ui/use-media-query";
import { DeleteEntryDialog } from "@/components/workspace/delete-entry-dialog";
import { WorkspaceEntryList } from "@/components/workspace/workspace-entry-list";
import { WorkspacePreview } from "@/components/workspace/workspace-preview";
import {
  deleteWorkspaceEntry,
  fetchWorkspaceEntries,
  fetchWorkspaceStatus,
  type WorkspaceEntry,
  type WorkspaceListing,
  type WorkspaceStatus,
} from "@/lib/workspace-api";
import {
  addressable,
  crumbs,
  explorerHref,
  joinPath,
  type WorkspaceEntryKind,
} from "@/lib/workspace-file";

/** 읽은 결과다. 실패하면 응답 상태를 둔다. 연결이 끊긴 실패는 0 이다. */
type Read<T> = { ok: true; data: T } | { ok: false; status: number };
type Listing = {
  path: string;
  read: Read<WorkspaceListing>;
  cursor: string | null;
  previous: (string | null)[];
};

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

/** 확인 창에 띄운 항목이다. 줄을 누른 때 이은 경로를 담아 두므로 그 뒤 주소가 바뀌어도 지우는 대상은 그대로다. */
type DeleteTarget = {
  path: string;
  name: string;
  kind: WorkspaceEntryKind;
  running: boolean;
};

/** 지우기 실패의 안내다. 문구는 `docs/features/workspace.md` 의 「파일 공간」 이 갖는다. 오류 코드는 그리지 않는다. */
const DELETE_FAILURES: Record<number, string> = {
  404: "찾을 수 없어요. 지워졌을 수 있어요.",
  409: "항목이 너무 많아 지우지 않았어요. 안쪽 폴더부터 지워 주세요.",
  502: "지우지 못했어요. 일부만 지워졌을 수 있어요.",
};

/**
 * 지우기 확인 창과 그 결과다. 창을 열 때 상태를 다시 읽어 도는 실행을 본다. 읽지 못하면 경고 없이 연다.
 * `settle`에 경로와 실패 상태(성공 null, 연결 실패 0)를 넘긴다. 실패 안내는 그 디렉터리에서만 보인다.
 * 상태 조회부터 창을 닫을 때까지 새 요청을 막아 사용자가 확인한 대상을 지킨다.
 */
function useEntryDeletion(
  dir: string,
  settle: (target: string, failure: number | null) => void,
) {
  const [target, setTarget] = useState<DeleteTarget | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ dir: string; text: string } | null>(
    null,
  );

  // 상태를 읽기 시작한 때부터 창을 닫을 때까지 참이다. 렌더를 기다리지 않고 바로 읽혀야 하므로 state 가 아니라 ref 다.
  const locked = useRef(false);
  const close = () => {
    locked.current = false;
    setTarget(null);
  };

  const ask = (path: string, entry: WorkspaceEntry) => {
    if (locked.current) return;
    locked.current = true;
    void readJson<WorkspaceStatus>(fetchWorkspaceStatus).then((read) => {
      setError(null);
      setTarget({
        path,
        name: entry.name,
        kind: entry.kind,
        running: read.ok && read.data.runningExecutions > 0,
      });
    });
  };

  const confirm = async () => {
    if (target === null || busy) return;
    setBusy(true);
    let failure: number | null = null;
    try {
      const response = await deleteWorkspaceEntry(target.path);
      if (!response.ok) failure = response.status;
    } catch {
      failure = 0;
    }
    if (failure !== null)
      setError({ dir, text: DELETE_FAILURES[failure] ?? "지우지 못했어요." });
    settle(target.path, failure);
    setBusy(false);
    close();
  };

  return {
    target,
    busy,
    error: error?.dir === dir ? error.text : null,
    ask,
    confirm,
    cancel: close,
  };
}

/**
 * 사용자 실행 공간의 목록과 미리보기다. 연 디렉터리와 미리 보는 파일은 주소의 `path`, `file` 이 정한다.
 * 지우기는 `deletable`일 때만 열며 409를 제외하면 목록을 다시 읽는다. 미리 보던 파일을 지우면 `file`을 지운다.
 */
export function WorkspaceExplorer() {
  const params = useSearchParams();
  const router = useRouter();
  const path = params.get("path") ?? "";
  const file = params.get("file");
  const wide = useMediaQuery("(min-width: 1024px)");
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<Read<WorkspaceStatus> | null>(null);
  const [listing, setListing] = useState<Listing | null>(null);
  const [pageBusy, setPageBusy] = useState(false);
  const [pageFailure, setPageFailure] = useState<number | null>(null);
  // 선택한 파일 한 건만 페이지 이동 중에도 보관한다.
  const [selectedFile, setSelectedFile] = useState<{
    path: string;
    entry: WorkspaceEntry;
  } | null>(null);
  const generation = useRef(0);
  const pageLocked = useRef(false);
  const [reload, setReload] = useState(0);
  const read = listing?.path === path ? listing.read : null;
  const currentSelection = read?.ok
    ? read.data.entries.find(
        (entry) =>
          entry.kind === "FILE" &&
          entry.readable &&
          entry.openable &&
          addressable(path) &&
          joinPath(path, entry.name) === file,
      )
    : undefined;
  const selected =
    currentSelection ??
    (selectedFile?.path === file ? selectedFile.entry : undefined);
  const reloadPage = () => {
    if (selected !== undefined && file !== null)
      setSelectedFile({ path: file, entry: selected });
    generation.current++;
    pageLocked.current = true;
    setPageBusy(true);
    setReload((value) => value + 1);
  };
  const deletion = useEntryDeletion(path, (target, failure) => {
    if (failure !== 409) reloadPage();
    if (
      failure === null &&
      file !== null &&
      (file === target || file.startsWith(`${target}/`))
    )
      router.push(explorerHref(path), { scroll: false });
  });

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
    const requestGeneration = ++generation.current;
    pageLocked.current = true;
    void readJson<WorkspaceListing>(() => fetchWorkspaceEntries(path)).then(
      (read) => {
        if (cancelled || generation.current !== requestGeneration) return;
        setListing({ path, read, cursor: null, previous: [] });
        setPageBusy(false);
        setPageFailure(null);
        pageLocked.current = false;
      },
    );
    return () => {
      cancelled = true;
    };
  }, [ready, attempt, reload, path]);

  const retry = () => {
    generation.current++;
    pageLocked.current = true;
    setStatus(null);
    setListing(null);
    setAttempt((value) => value + 1);
  };

  const movePage = (cursor: string | null, previous: (string | null)[]) => {
    if (pageLocked.current) return;
    pageLocked.current = true;
    const requestGeneration = ++generation.current;
    if (selected !== undefined && file !== null)
      setSelectedFile({ path: file, entry: selected });
    setPageBusy(true);
    setPageFailure(null);
    void readJson<WorkspaceListing>(() =>
      fetchWorkspaceEntries(path, cursor),
    ).then((read) => {
      if (generation.current !== requestGeneration) return;
      if (read.ok) setListing({ path, read, cursor, previous });
      else setPageFailure(read.status);
      setPageBusy(false);
      pageLocked.current = false;
    });
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

  let content = EMPTY;
  if (status.data.exists && read === null) {
    content = LOADING;
  } else if (read !== null && !read.ok) {
    content = <Failure status={read.status} onRetry={retry} />;
  } else if (read !== null && listing !== null) {
    const { entries, nextCursor } = read.data;
    content = (
      <WorkspaceEntryList
        path={path}
        entries={entries}
        nextCursor={nextCursor}
        hasPrevious={listing.previous.length > 0}
        busy={pageBusy}
        onNext={() => {
          if (nextCursor !== null)
            movePage(nextCursor, [...listing.previous, listing.cursor]);
        }}
        onPrevious={() => {
          if (listing.previous.length > 0)
            movePage(
              listing.previous.at(-1) ?? null,
              listing.previous.slice(0, -1),
            );
        }}
        onReload={reloadPage}
        selected={selected === undefined ? null : file}
        onDelete={status.data.deletable ? deletion.ask : null}
      />
    );
  }

  return (
    <div className="space-y-4">
      <SharedAgents status={status.data} />
      <div className="flex min-w-0 items-start gap-4">
        <div className="min-w-0 flex-1 space-y-3">
          <Crumbs path={path} />
          {deletion.error === null ? null : (
            <Notice variant="error" role="alert">
              {deletion.error}
            </Notice>
          )}
          {content}
          {pageFailure === null ? null : (
            <Failure status={pageFailure} onRetry={reloadPage} />
          )}
        </div>
        {selected === undefined || file === null ? null : (
          <WorkspacePreview
            path={file}
            name={selected.name}
            size={selected.size}
            wide={wide}
            onClose={() => router.push(explorerHref(path), { scroll: false })}
          />
        )}
      </div>
      {deletion.target === null ? null : (
        <DeleteEntryDialog
          name={deletion.target.name}
          kind={deletion.target.kind}
          running={deletion.target.running}
          busy={deletion.busy}
          onCancel={deletion.cancel}
          onConfirm={() => void deletion.confirm()}
        />
      )}
    </div>
  );
}
