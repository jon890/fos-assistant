import { Skeleton } from "@/components/ui/skeleton";

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
    <div className="flex h-full min-h-0 flex-col gap-3">
      <button
        type="button"
        onClick={onNew}
        className="rounded-md border border-border px-3 py-2 text-left text-sm"
      >
        새 대화
      </button>

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
                  <button
                    type="button"
                    onClick={() => onSelect(conversation)}
                    aria-current={selected ? "true" : undefined}
                    className={`w-full rounded-md border px-3 py-2 text-left text-sm ${
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
                  </button>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </div>
  );
}
