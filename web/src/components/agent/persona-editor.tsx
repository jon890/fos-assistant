"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Textarea } from "@/components/ui/textarea";
import { describeError } from "@/components/error-message";
import type { PersonaView } from "@/lib/agent";
import { fetchPersona, savePersona } from "@/lib/agent-api";
import { PersonaConfirm } from "./persona-confirm";

type Props = {
  code: string;
  name: string;
  initialPersona: PersonaView;
};

type ErrorPayload = { code: string; message: string };

/** 저장 요청이 지금 어느 단계인지다. */
type SaveState = "idle" | "saving" | "saved";

async function readPersona(code: string): Promise<PersonaView | null> {
  const response = await fetchPersona(code);
  if (!response.ok) return null;
  return (await response.json()) as PersonaView;
}

export function PersonaEditor({ code, name, initialPersona }: Props) {
  const [body, setBody] = useState(initialPersona.body);
  const [bodyHash, setBodyHash] = useState(initialPersona.bodyHash);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  /** 다른 곳에서 먼저 저장돼 다시 읽어 온 본문이다. 편집창의 글을 덮지 않고 나란히 보여 준다. */
  const [serverBody, setServerBody] = useState<string | null>(null);

  const editable = initialPersona.editable;
  const maxChars = initialPersona.maxChars;
  // 백엔드가 앞뒤 공백을 떼고 저장하므로 화면도 다듬은 길이로 센다.
  const trimmedLength = body.trim().length;
  const remaining = maxChars - trimmedLength;
  const isEmpty = trimmedLength === 0;
  const busy = saveState === "saving";

  function edit(value: string) {
    setBody(value);
    setSaveState("idle");
    setError(null);
  }

  async function save() {
    setConfirming(false);
    setSaveState("saving");
    setError(null);
    setServerBody(null);
    try {
      const response = await savePersona(code, { body, baseHash: bodyHash });
      if (!response.ok) {
        const failure = (await response.json()) as ErrorPayload;
        if (failure.code === "PERSONA_STALE") {
          // 쓰던 글을 덮지 않는다. 지문만 최신으로 바꾸고 서버 본문은 따로 보여 준다.
          const fresh = await readPersona(code);
          if (fresh !== null) {
            setBodyHash(fresh.bodyHash);
            setServerBody(fresh.body);
          }
        }
        setError(describeError(failure.code, failure.message));
        setSaveState("idle");
        return;
      }
      const saved = (await response.json()) as PersonaView;
      setBody(saved.body);
      setBodyHash(saved.bodyHash);
      setSaveState("saved");
    } catch {
      setError(describeError("HERMES_UNAVAILABLE", "저장하지 못했어요."));
      setSaveState("idle");
    }
  }

  return (
    <div className="mx-auto w-full max-w-2xl">
      <h1 className="mb-2 text-xl font-semibold">{name}</h1>
      <p className="mb-6 text-sm leading-6 text-muted-foreground">
        이 에이전트가 대화마다 따르는 성격이에요. 저장한 내용은 다음 대화부터
        반영돼요.
      </p>
      {isEmpty ? (
        <p className="mb-3 text-sm text-muted-foreground">
          아직 성격을 쓰지 않았어요.
        </p>
      ) : null}
      {/* Textarea 의 기본 field-sizing-content 는 rows 를 무시해 편집창이 작게 시작하므로 고정으로 되돌린다. */}
      <Textarea
        value={body}
        onChange={(event) => edit(event.target.value)}
        readOnly={!editable}
        rows={16}
        aria-label={`${name} 성격`}
        className="field-sizing-fixed leading-6"
      />
      <p className="mt-2 text-xs text-muted-foreground">남은 {remaining}자</p>
      {error ? (
        <Notice variant="error" role="alert" className="mt-2 break-all">
          {error}
        </Notice>
      ) : null}
      {serverBody !== null ? (
        <section className="mt-3 rounded-md border border-border p-3">
          <h2 className="text-sm font-semibold">지금 저장되어 있는 성격</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            작성하던 글은 위 편집창에 그대로 있어요. 현재 저장된 글과 비교해
            남길 내용을 정한 뒤 다시 저장해 주세요.
          </p>
          <pre className="mt-2 text-sm leading-6 whitespace-pre-wrap break-all">
            {serverBody}
          </pre>
        </section>
      ) : null}
      {saveState === "saved" ? (
        <p className="mt-2 text-sm">저장했어요.</p>
      ) : null}
      {editable ? (
        <Button
          onClick={() => setConfirming(true)}
          disabled={remaining < 0 || isEmpty}
          loading={busy}
          loadingText="저장 중"
          className="mt-4"
        >
          저장
        </Button>
      ) : null}
      {confirming ? (
        <PersonaConfirm
          agentName={name}
          busy={busy}
          onCancel={() => setConfirming(false)}
          onConfirm={() => void save()}
        />
      ) : null}
    </div>
  );
}
