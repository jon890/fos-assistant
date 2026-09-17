export type Conversation = {
  id: number;
  title: string;
  workspaceCode: string | null;
  updatedAt: string;
};

type Props = {
  conversations: Conversation[];
  selectedId: number | null;
  onSelect(conversation: Conversation): void;
  onNew(): void;
};

export function ConversationList({ conversations, selectedId, onSelect, onNew }: Props) {
  return (
    <aside className="flex flex-col gap-3">
      <button
        type="button"
        onClick={onNew}
        className="rounded-md border px-3 py-2 text-left text-sm"
        style={{ borderColor: "var(--border)" }}
      >
        새 대화
      </button>

      {conversations.length === 0 ? (
        <p className="text-sm" style={{ color: "var(--muted)" }}>
          아직 대화가 없다. 새 대화를 시작한다.
        </p>
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
                  className="w-full rounded-md border px-3 py-2 text-left text-sm"
                  style={{
                    borderColor: selected ? "var(--foreground)" : "var(--border)",
                    background: selected ? "var(--surface)" : "transparent",
                  }}
                >
                  <span className="block font-medium">{conversation.title}</span>
                  <span className="mt-1 block text-xs" style={{ color: "var(--muted)" }}>
                    {new Date(conversation.updatedAt).toLocaleString("ko-KR")}
                    {conversation.workspaceCode ? ` · ${conversation.workspaceCode}` : ""}
                  </span>
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </aside>
  );
}
