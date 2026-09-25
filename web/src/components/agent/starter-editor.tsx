"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { describeError } from "@/components/error-message";
import type { StartersView } from "@/lib/agent";

type Props = {
  code: string;
  name: string;
  initialStarters: StartersView;
};

type ErrorPayload = { code: string; message: string };

/** 저장 요청이 지금 어느 단계인지다. */
type SaveState = "idle" | "saving" | "saved";

/** 추천 질문 칸은 언제나 `maxPrompts` 개를 그린다. 모자란 줄은 빈 문자열로 채운다. */
function fillPrompts(starterPrompts: string[], maxPrompts: number): string[] {
  const filled = starterPrompts.slice(0, maxPrompts);
  while (filled.length < maxPrompts) filled.push("");
  return filled;
}

export function StarterEditor({ code, name, initialStarters }: Props) {
  const [tagline, setTagline] = useState(initialStarters.tagline ?? "");
  const [prompts, setPrompts] = useState(
    fillPrompts(initialStarters.starterPrompts, initialStarters.maxPrompts),
  );
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [error, setError] = useState<string | null>(null);

  const editable = initialStarters.editable;
  const busy = saveState === "saving";

  function editTagline(value: string) {
    setTagline(value);
    setSaveState("idle");
    setError(null);
  }

  function editPrompt(index: number, value: string) {
    setPrompts((prev) => prev.map((prompt, i) => (i === index ? value : prompt)));
    setSaveState("idle");
    setError(null);
  }

  async function save() {
    setSaveState("saving");
    setError(null);
    try {
      const response = await fetch(`/api/agents/${code}/starters`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tagline, starterPrompts: prompts }),
      });
      if (!response.ok) {
        const failure = (await response.json()) as ErrorPayload;
        setError(describeError(failure.code, failure.message));
        setSaveState("idle");
        return;
      }
      const saved = (await response.json()) as StartersView;
      setTagline(saved.tagline ?? "");
      setPrompts(fillPrompts(saved.starterPrompts, saved.maxPrompts));
      setSaveState("saved");
    } catch {
      // 소개와 추천 질문은 Control Plane 에만 저장하고 Hermes 를 부르지 않는다. 요청 자체가 닿지 못한 경우다.
      setError("저장하지 못했다. 잠시 뒤 다시 시도해 주세요.");
      setSaveState("idle");
    }
  }

  return (
    <div className="mx-auto mt-8 w-full max-w-2xl">
      <h2 className="mb-2 text-lg font-semibold">새 대화 화면</h2>
      <p className="mb-4 text-sm leading-6 text-muted">새 대화에서 이 에이전트를 고르면 보인다.</p>
      <input
        type="text"
        value={tagline}
        onChange={(event) => editTagline(event.target.value)}
        readOnly={!editable}
        maxLength={200}
        aria-label={`${name} 소개`}
        className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
      />
      <div className="mt-3 flex flex-col gap-2">
        {prompts.map((prompt, index) => (
          <input
            key={index}
            type="text"
            value={prompt}
            onChange={(event) => editPrompt(index, event.target.value)}
            readOnly={!editable}
            maxLength={300}
            aria-label={`추천 질문 ${index + 1}`}
            className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm"
          />
        ))}
      </div>
      {error ? (
        <p role="alert" className="mt-2 rounded-md bg-surface p-3 text-sm break-all">
          {error}
        </p>
      ) : null}
      {saveState === "saved" ? <p className="mt-2 text-sm">소개와 추천 질문이 저장되었습니다.</p> : null}
      {editable ? (
        <Button onClick={() => void save()} disabled={busy} className="mt-4">
          {busy ? "저장 중…" : "소개와 추천 질문 저장"}
        </Button>
      ) : null}
    </div>
  );
}
