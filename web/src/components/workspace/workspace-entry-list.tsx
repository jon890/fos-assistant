import Link from "next/link";
import {
  File,
  FileQuestion,
  Folder,
  Link2,
  type LucideIcon,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatFullTime } from "@/lib/format";
import type { WorkspaceEntry } from "@/lib/workspace-api";
import {
  explorerHref,
  fileUrl,
  formatSize,
  joinPath,
  type WorkspaceEntryKind,
} from "@/lib/workspace-file";

const ICONS: Record<WorkspaceEntryKind, LucideIcon> = {
  DIRECTORY: Folder,
  FILE: File,
  LINK: Link2,
  OTHER: FileQuestion,
};

/**
 * 목록의 한 줄이다. 디렉터리는 목록으로, 파일은 미리보기로 가는 링크다.
 *
 * <p>미리보기와 내려받기는 읽을 수 있고 주소로 쓸 수 있는 이름의 파일만 연다. 그 밖의 이름은 Control Plane 이 거절한다.
 */
function EntryRow({
  path,
  entry,
  selected,
}: {
  path: string;
  entry: WorkspaceEntry;
  selected: boolean;
}) {
  const Icon = ICONS[entry.kind];
  const target = joinPath(path, entry.name);
  const openable = entry.kind === "FILE" && entry.readable && entry.openable;
  const href =
    entry.kind === "DIRECTORY" && entry.readable
      ? explorerHref(target)
      : openable
        ? explorerHref(path, target)
        : null;
  return (
    <TableRow
      data-testid="workspace-entry"
      data-state={selected ? "selected" : undefined}
    >
      <TableCell className="space-x-2 whitespace-normal">
        <Icon
          aria-hidden="true"
          className="inline size-4 align-text-bottom text-muted-foreground"
        />
        {href === null ? (
          <span className="break-all">{entry.name}</span>
        ) : (
          <Link
            href={href}
            prefetch={false}
            scroll={false}
            className="break-all hover:underline"
          >
            {entry.name}
          </Link>
        )}
        {entry.kind === "LINK" ? <Badge variant="outline">링크</Badge> : null}
        {entry.readable ? null : <Badge variant="outline">읽을 수 없음</Badge>}
        {entry.kind === "FILE" && entry.readable && !entry.openable ? (
          <Badge variant="warning">주소로 열 수 없는 이름</Badge>
        ) : null}
      </TableCell>
      <TableCell className="text-right text-muted-foreground tabular-nums">
        {entry.size === null ? "" : formatSize(entry.size)}
      </TableCell>
      <TableCell className="hidden text-muted-foreground sm:table-cell">
        {formatFullTime(entry.modifiedAt)}
      </TableCell>
      <TableCell>
        {openable ? (
          <a
            href={fileUrl(target, true)}
            aria-label={`${entry.name} 내려받기`}
            className="underline"
          >
            내려받기
          </a>
        ) : null}
      </TableCell>
    </TableRow>
  );
}

/**
 * 디렉터리 하나의 목록이다. 줄 순서는 Control Plane 이 정한 그대로다(디렉터리 먼저, 이름 순서).
 *
 * <p>링크는 모두 `prefetch={false}` 다. 줄 수가 정해지지 않은 목록이라 미리 읽으면 요청이 줄마다 나간다.
 */
export function WorkspaceEntryList({
  path,
  entries,
  truncated,
  selected,
}: {
  path: string;
  entries: WorkspaceEntry[];
  truncated: boolean;
  selected: string | null;
}) {
  return (
    <div className="space-y-2">
      <Table data-testid="workspace-entries">
        <TableHeader>
          <TableRow>
            <TableHead>이름</TableHead>
            <TableHead className="text-right">크기</TableHead>
            <TableHead className="hidden sm:table-cell">바뀐 시각</TableHead>
            <TableHead>
              <span className="sr-only">동작</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => (
            <EntryRow
              key={entry.name}
              path={path}
              entry={entry}
              selected={selected === joinPath(path, entry.name)}
            />
          ))}
        </TableBody>
      </Table>
      {truncated ? (
        <p className="text-sm text-muted-foreground">1,000개까지만 보여요</p>
      ) : null}
    </div>
  );
}
