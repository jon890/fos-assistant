import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import type { Person } from "@/lib/people";

type Props = {
  people: Person[];
  busy: boolean;
  onEnabledChange(person: Person): void;
};

const cellClass = "px-3 py-2 align-middle whitespace-nowrap";

export function PersonList({ people, busy, onEnabledChange }: Props) {
  if (people.length === 0) {
    return <EmptyState title="아직 아무도 없습니다" description="위 양식에서 첫 사람을 더한다." />;
  }
  return (
    <div className="overflow-x-auto rounded-md border border-border">
      <table aria-label="더해진 사람" className="w-full border-collapse text-sm">
        <thead className="bg-surface text-left text-muted">
          <tr>
            <th scope="col" className={cellClass}>이름</th>
            <th scope="col" className={cellClass}>이메일</th>
            <th scope="col" className={cellClass}>profile</th>
            <th scope="col" className={cellClass}>첫 로그인</th>
            <th scope="col" className={cellClass}>로그인</th>
            <th scope="col" className={cellClass}>바꾸기</th>
          </tr>
        </thead>
        <tbody>
          {people.map((person) => (
            <tr key={person.id} className="border-t border-border">
              <th scope="row" className={`${cellClass} text-left font-medium`}>
                {person.displayName}
              </th>
              <td className={cellClass}>{person.email}</td>
              <td className={cellClass}>{person.hermesProfile}</td>
              <td className={cellClass}>{person.joined ? "들어온 적 있음" : "아직 없음"}</td>
              <td className={cellClass}>{person.enabled ? "켜짐" : "꺼짐"}</td>
              <td className={cellClass}>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={busy}
                  aria-label={`${person.displayName} ${person.enabled ? "사용 중지" : "다시 허용"}`}
                  onClick={() => onEnabledChange(person)}
                >
                  {person.enabled ? "사용 중지" : "다시 허용"}
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
