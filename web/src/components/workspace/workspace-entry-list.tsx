import Link from "next/link";
import {
  File,
  FileQuestion,
  Folder,
  Link2,
  type LucideIcon,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
  addressable,
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
 * 지우기는 경로를 `path` 인자로 보내므로 종류와 이름에 상관없이 그린다.
 */
function EntryRow({
  path,
  entry,
  selected,
  onDelete,
}: {
  path: string;
  entry: WorkspaceEntry;
  selected: boolean;
  onDelete: ((target: string, entry: WorkspaceEntry) => void) | null;
}) {
  const Icon = ICONS[entry.kind];
  const target = joinPath(path, entry.name);
  // 디렉터리 경로를 주소로 쓸 수 없으면 그 안의 파일도 주소로 열 수 없다.
  const openable =
    entry.kind === "FILE" &&
    entry.readable &&
    entry.openable &&
    addressable(path);
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
        {entry.kind === "FILE" && entry.readable && !openable ? (
          <Badge variant="warning">주소로 열 수 없는 이름</Badge>
        ) : null}
      </TableCell>
      <TableCell className="text-right text-muted-foreground tabular-nums">
        {entry.size === null ? "" : formatSize(entry.size)}
      </TableCell>
      <TableCell className="hidden text-muted-foreground sm:table-cell">
        {formatFullTime(entry.modifiedAt)}
      </TableCell>
      <TableCell className="space-x-3">
        {openable ? (
          <a
            href={fileUrl(target, true)}
            aria-label={`${entry.name} 내려받기`}
            className="underline"
          >
            내려받기
          </a>
        ) : null}
        {onDelete === null ? null : (
          <Button
            variant="link"
            size="xs"
            aria-label={`${entry.name} 지우기`}
            className="px-0 text-sm text-destructive hover:text-destructive/80"
            onClick={() => onDelete(target, entry)}
          >
            지우기
          </Button>
        )}
      </TableCell>
    </TableRow>
  );
}

/**
 * 디렉터리 하나의 목록이다. 줄 순서는 Control Plane 이 정한 그대로다(디렉터리 먼저, 이름 순서).
 *
 * <p>링크는 모두 `prefetch={false}` 다. 줄 수가 정해지지 않은 목록이라 미리 읽으면 요청이 줄마다 나간다.
 *
 * <p>`onDelete` 가 null 이면 「지우기」 를 그리지 않는다. 넘기는 경로는 이 목록의 `path` 와 줄 이름을 이은 것 하나다.
 */
export function WorkspaceEntryList({
  path,
  entries,
  truncated,
  selected,
  onDelete,
}: {
  path: string;
  entries: WorkspaceEntry[];
  truncated: boolean;
  selected: string | null;
  onDelete: ((target: string, entry: WorkspaceEntry) => void) | null;
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
              onDelete={onDelete}
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
