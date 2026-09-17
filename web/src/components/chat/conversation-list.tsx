import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";

export type Conversation = {
  id: number;
  title: string;
  workspaceCode: string | null;
  agentCode: string;
  agentName: string;
  updatedAt: string;
};

type Props = {
  conversations: Conversation[];
  selectedId: number | null;
  loading: boolean;
  onSelect(conversation: Conversation): void;
  onNew(): void;
};

export function ConversationList({
  conversations,
  selectedId,
  loading,
  onSelect,
  onNew,
}: Props) {
  return (
    <div className="flex h-full min-h-0 flex-col gap-3 rounded-md bg-surface-raised p-3">
      <Button
        onClick={onNew}
        size="sm"
        variant="secondary"
        className="justify-start"
      >
        새 대화
      </Button>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div aria-label="대화 목록을 읽는 중" className="flex flex-col gap-2">
            <Skeleton className="h-[3.875rem]" />
            <Skeleton className="h-[3.875rem]" />
            <Skeleton className="h-[3.875rem]" />
          </div>
        ) : conversations.length === 0 ? (
          <p className="text-sm text-muted">아직 대화가 없다. 새 대화를 시작한다.</p>
        ) : (
          <ol className="flex flex-col gap-2">
            {conversations.map((conversation) => {
              const selected = conversation.id === selectedId;
              return (
                <li key={conversation.id}>
                  <Button
                    onClick={() => onSelect(conversation)}
                    aria-current={selected ? "true" : undefined}
                    size="sm"
                    variant="secondary"
                    className={`h-auto w-full justify-start text-left ${
                      selected ? "border-foreground bg-surface" : "border-border"
                    }`}
                  >
                    <span className="block truncate font-medium" title={conversation.title}>
                      {conversation.title}
                    </span>
                    <span className="mt-1 block truncate text-xs text-muted">
                      {new Date(conversation.updatedAt).toLocaleString("ko-KR")}
                      {conversation.workspaceCode ? ` · ${conversation.workspaceCode}` : ""}
                    </span>
                  </Button>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </div>
  );
}
