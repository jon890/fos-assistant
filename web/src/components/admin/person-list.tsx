import * as React from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { Person } from "@/lib/people";
import { PersonActivity } from "./person-activity";

type Props = {
  people: Person[];
  /** 어느 요청이든 돌고 있다. 그동안 모든 줄의 단추를 잠근다. */
  busy: boolean;
  /** 켜고 끄는 요청이 도는 사람이다. 그 줄의 단추에만 회전 표시를 둔다. */
  pendingId: Person["id"] | null;
  readAt: string;
  onEnabledChange(person: Person): void;
};

export function PersonList({
  people,
  busy,
  pendingId,
  readAt,
  onEnabledChange,
}: Props) {
  if (people.length === 0) {
    return (
      <EmptyState
        title="아직 등록된 사용자가 없어요"
        description="위 양식에서 첫 사용자를 추가해 주세요."
      />
    );
  }
  return (
    <div className="min-w-0 rounded-md border border-border">
      <Table aria-label="등록된 사용자">
        <TableHeader className="bg-muted">
          <TableRow className="hover:bg-transparent">
            <TableHead scope="col" className="px-3">
              이름
            </TableHead>
            <TableHead scope="col" className="px-3">
              이메일
            </TableHead>
            <TableHead scope="col" className="px-3">
              profile
            </TableHead>
            <TableHead scope="col" className="px-3">
              마지막 로그인
            </TableHead>
            <TableHead scope="col" className="px-3">
              마지막 대화
            </TableHead>
            <TableHead scope="col" className="px-3">
              로그인
            </TableHead>
            <TableHead scope="col" className="px-3">
              바꾸기
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {people.map((person) => (
            <PersonRows
              key={person.id}
              person={person}
              busy={busy}
              pendingId={pendingId}
              readAt={readAt}
              onEnabledChange={onEnabledChange}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function PersonRows({
  person,
  busy,
  pendingId,
  readAt,
  onEnabledChange,
}: Omit<Props, "people"> & { person: Person }) {
  const [expanded, setExpanded] = React.useState(false);
  const detailId = `person-detail-${person.id}`;
  const hasSignedIn = person.joined || Boolean(person.lastLoginAt);
  return (
    <>
      <TableRow>
        <TableHead scope="row" className="px-3 text-foreground">
          {person.displayName}
        </TableHead>
        <TableCell className="px-3">{person.email}</TableCell>
        <TableCell className="px-3">{person.hermesProfile}</TableCell>
        <TableCell className="px-3">
          <PersonActivity value={person.lastLoginAt} readAt={readAt} />
        </TableCell>
        <TableCell className="px-3">
          <PersonActivity value={person.lastConversationAt} readAt={readAt} />
        </TableCell>
        <TableCell className="px-3">
          <Badge variant={person.enabled ? "success" : "warning"}>
            {person.enabled ? "켜짐" : "꺼짐"}
          </Badge>
        </TableCell>
        <TableCell className="px-3">
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              aria-expanded={expanded}
              aria-controls={detailId}
              onClick={() => setExpanded((current) => !current)}
            >
              상세
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              loading={pendingId === person.id}
              loadingText="바꾸는 중"
              aria-label={`${person.displayName} ${person.enabled ? "사용 중지" : "다시 허용"}`}
              onClick={() => onEnabledChange(person)}
            >
              {person.enabled ? "사용 중지" : "다시 허용"}
            </Button>
          </div>
        </TableCell>
      </TableRow>
      {expanded ? (
        <TableRow id={detailId}>
          <TableCell
            colSpan={7}
            className="whitespace-normal bg-muted/40 px-4 py-3"
          >
            <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
              <ActivityDetail label="이름">{person.displayName}</ActivityDetail>
              <ActivityDetail label="이메일">{person.email}</ActivityDetail>
              <ActivityDetail label="profile">
                {person.hermesProfile}
              </ActivityDetail>
              <ActivityDetail label="첫 로그인 여부">
                {hasSignedIn ? "로그인한 적 있음" : "로그인한 적 없음"}
              </ActivityDetail>
              <ActivityDetail label="마지막 로그인">
                <PersonActivity value={person.lastLoginAt} readAt={readAt} />
              </ActivityDetail>
              <ActivityDetail label="마지막 대화">
                <PersonActivity
                  value={person.lastConversationAt}
                  readAt={readAt}
                />
              </ActivityDetail>
            </dl>
          </TableCell>
        </TableRow>
      ) : null}
    </>
  );
}

function ActivityDetail({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
