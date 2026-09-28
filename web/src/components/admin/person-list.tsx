import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Person } from "@/lib/people";

type Props = {
  people: Person[];
  /** 어느 요청이든 돌고 있다. 그동안 모든 줄의 단추를 잠근다. */
  busy: boolean;
  /** 켜고 끄는 요청이 도는 사람이다. 그 줄의 단추에만 회전 표시를 둔다. */
  pendingId: Person["id"] | null;
  onEnabledChange(person: Person): void;
};

export function PersonList({ people, busy, pendingId, onEnabledChange }: Props) {
  if (people.length === 0) {
    return <EmptyState title="아직 등록된 사용자가 없어요" description="위 양식에서 첫 사용자를 추가해 주세요." />;
  }
  return (
    <div className="rounded-md border border-border">
      <Table aria-label="등록된 사용자">
        <TableHeader className="bg-muted">
          <TableRow className="hover:bg-transparent">
            <TableHead scope="col" className="px-3">이름</TableHead>
            <TableHead scope="col" className="px-3">이메일</TableHead>
            <TableHead scope="col" className="px-3">profile</TableHead>
            <TableHead scope="col" className="px-3">첫 로그인</TableHead>
            <TableHead scope="col" className="px-3">로그인</TableHead>
            <TableHead scope="col" className="px-3">바꾸기</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {people.map((person) => (
            <TableRow key={person.id}>
              <TableHead scope="row" className="px-3 text-foreground">{person.displayName}</TableHead>
              <TableCell className="px-3">{person.email}</TableCell>
              <TableCell className="px-3">{person.hermesProfile}</TableCell>
              <TableCell className="px-3">{person.joined ? "로그인한 적 있음" : "로그인한 적 없음"}</TableCell>
              <TableCell className="px-3">
                <Badge variant={person.enabled ? "outline" : "default"}>{person.enabled ? "켜짐" : "꺼짐"}</Badge>
              </TableCell>
              <TableCell className="px-3">
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
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
