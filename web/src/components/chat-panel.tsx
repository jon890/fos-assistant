"use client";

import { useState } from "react";
import { describeError } from "./error-message";

type Turn = { role: "USER" | "ASSISTANT"; text: string };

export function ChatPanel() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const text = draft.trim();
    if (text.length === 0 || busy) return;

    setBusy(true);
    setError(null);
    setDraft("");
    setTurns((previous) => [...previous, { role: "USER", text }]);

    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ conversationId, text }),
      });
      const payload = await response.json();
      if (!response.ok) {
        setError(describeError(payload.code, payload.message));
        return;
      }
      setConversationId(payload.conversationId);
      setTurns((previous) => [...previous, { role: "ASSISTANT", text: payload.assistantText }]);
    } catch {
      setError("요청을 보내지 못했다.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="flex flex-col gap-4">
      <ol className="flex flex-col gap-3">
        {turns.map((turn, index) => (
          <li
            key={index}
            className="rounded-lg px-3 py-2 text-sm whitespace-pre-wrap"
            style={{
              background: turn.role === "USER" ? "var(--surface)" : "transparent",
              border: turn.role === "ASSISTANT" ? "1px solid var(--border)" : "none",
            }}
          >
            <span className="mb-1 block text-xs" style={{ color: "var(--muted)" }}>
              {turn.role === "USER" ? "나" : "비서"}
            </span>
            {turn.text}
          </li>
        ))}
        {busy ? (
          <li className="text-sm" style={{ color: "var(--muted)" }}>
            비서가 실행 중이다.
          </li>
        ) : null}
      </ol>

      {error ? (
        <p className="rounded-md px-3 py-2 text-sm" style={{ background: "var(--surface)" }}>
          {error}
        </p>
      ) : null}

      <form onSubmit={send} className="flex gap-2">
        <input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="무엇을 도와줄까요"
          className="flex-1 rounded-md border px-3 py-2 text-sm"
          style={{ borderColor: "var(--border)", background: "transparent" }}
        />
        <button
          type="submit"
          disabled={busy}
          className="rounded-md border px-4 py-2 text-sm disabled:opacity-50"
          style={{ borderColor: "var(--border)" }}
        >
          보내기
        </button>
      </form>
    </section>
  );
}
