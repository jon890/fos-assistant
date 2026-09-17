import { Markdown } from "./markdown";

export type Turn = {
  id: number | string;
  role: "USER" | "ASSISTANT";
  content: string;
  senderName: string | null;
};

export function MessageBubble({ turn }: { turn: Turn }) {
  const user = turn.role === "USER";
  return (
    <li
      className={`min-w-0 rounded-lg px-3 py-2 ${
        user ? "bg-surface" : "border border-border"
      }`}
    >
      <span className="mb-1 block text-xs text-muted">
        {user ? turn.senderName ?? "나" : "비서"}
      </span>
      {user ? (
        <p className="whitespace-pre-wrap break-words text-sm leading-6">{turn.content}</p>
      ) : (
        <Markdown>{turn.content}</Markdown>
      )}
    </li>
  );
}
