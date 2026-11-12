"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import {
  AGENT_DESCRIPTION_MAX_CHARS,
  AGENT_NAME_MAX_CHARS,
  initialOf,
  type AgentColor,
} from "@/lib/agent-badge";
import type { BoundAgent } from "@/lib/connection";
import {
  DETACH_LOCKED_REASON,
  createSpecialistAgent,
  detachOption,
  specialistDraft,
  type DetachOption,
  type SpecialistAgentResult,
} from "@/lib/specialist-agent";
import { AgentBadge } from "./agent-badge";
import { IntroductionPickers } from "./agent-introduction-section";

/**
 * 실패 까닭 뒤에 붙인다. 서버는 만들기 뒤 단계가 실패하면 새 에이전트를 지우고 오류를 돌려준다. 지우기마저 실패하면
 * 에이전트가 남을 수 있어 목록을 보라고 함께 적는다.
 */
const WITHDRAWN =
  " 만들던 에이전트를 거뒀어요. 에이전트 목록에 남아 있으면 지워 주세요.";

/** 아무것도 만들기 전에 거절되는 코드다. 이 코드에는 거뒀다는 글을 붙이지 않는다(design 「PR3 마법사」 의 거절 표). */
const REFUSED_BEFORE_CREATING = new Set([
  "AGENT_LIMIT_REACHED",
  "VALIDATION_FAILED",
  "CONNECTOR_NOT_CONNECTED",
  "CONNECTOR_NOT_FOUND",
  "CONNECTOR_SINGLE_BINDING",
  "UNAUTHENTICATED",
  "CONNECTOR_UNAVAILABLE",
]);

/**
 * 연결 하나만 맡는 전문 에이전트를 만드는 화면이다(docs/features/agent-team/design.md 의 「PR3 마법사」).
 * 이름, 소개, 아이콘을 미리 채우고, 그 연결이 붙은 다른 에이전트가 있으면 떼기 칸을 켠 채 보인다.
 * 연결을 마친 화면과 에이전트 목록이 같은 부품을 쓴다.
 */
export function SpecialistAgentForm({
  connectorId,
  title,
  bindings,
  singleBinding,
  cancelLabel,
  onCancel,
  onCreated,
}: {
  connectorId: string;
  title: string;
  /** 그 연결이 지금 붙은 에이전트들이다. */
  bindings: readonly BoundAgent[];
  singleBinding: boolean;
  cancelLabel: string;
  onCancel(): void;
  onCreated(result: SpecialistAgentResult): void;
}) {
  const id = useId();
  const draft = specialistDraft(title);
  const [name, setName] = useState(draft.name);
  const [description, setDescription] = useState(draft.description);
  const [icon, setIcon] = useState<string | null>(draft.icon);
  const [color, setColor] = useState<AgentColor | null>(null);
  const detach = detachOption(bindings, singleBinding);
  const [detachChecked, setDetachChecked] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmedName = name.trim();
  const descriptionLength = description.trim().length;
  const invalid =
    trimmedName.length === 0 ||
    trimmedName.length > AGENT_NAME_MAX_CHARS ||
    descriptionLength > AGENT_DESCRIPTION_MAX_CHARS;

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (invalid || creating) return;
    setCreating(true);
    setError(null);
    const result = await createSpecialistAgent(connectorId, {
      name: trimmedName,
      description: description.trim(),
      icon,
      color,
      detach: detach !== null && (detach.locked || detachChecked),
    });
    if (!result.ok) {
      setCreating(false);
      setError(
        REFUSED_BEFORE_CREATING.has(result.code)
          ? result.message
          : result.message + WITHDRAWN,
      );
      return;
    }
    onCreated(result.data);
  }

  return (
    <form
      onSubmit={(event) => void create(event)}
      className="grid gap-4"
      data-testid="specialist-agent-form"
    >
      <div className="flex items-center gap-3">
        <AgentBadge
          agent={{
            name: trimmedName || draft.name,
            code: connectorId,
            icon,
            color,
          }}
        />
        <p className="min-w-0 truncate text-sm">
          「{title}」 하나만 맡는 에이전트를 만들어요. 도구는 새 에이전트
          기본값이고 이 서비스의 스킬이 함께 붙어요.
        </p>
      </div>
      <label className="grid gap-1 text-sm" htmlFor={`${id}-name`}>
        이름
        <Input
          id={`${id}-name`}
          value={name}
          maxLength={AGENT_NAME_MAX_CHARS}
          required
          disabled={creating}
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      <label className="grid gap-1 text-sm" htmlFor={`${id}-description`}>
        한 줄 소개
        <Input
          id={`${id}-description`}
          value={description}
          disabled={creating}
          onChange={(event) => setDescription(event.target.value)}
        />
        <span className="text-xs text-muted-foreground">
          {descriptionLength <= AGENT_DESCRIPTION_MAX_CHARS
            ? `${AGENT_DESCRIPTION_MAX_CHARS - descriptionLength}자 더 쓸 수 있어요`
            : `${descriptionLength - AGENT_DESCRIPTION_MAX_CHARS}자 넘게 썼어요`}
        </span>
      </label>
      <IntroductionPickers
        code={connectorId}
        initialLetter={initialOf(trimmedName || draft.name)}
        icon={icon}
        color={color}
        onIcon={setIcon}
        onColor={setColor}
      />
      {detach ? (
        <DetachField
          option={detach}
          checked={detach.locked || detachChecked}
          disabled={detach.locked || creating}
          onChange={setDetachChecked}
        />
      ) : null}
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button
          type="submit"
          loading={creating}
          loadingText="만드는 중…"
          disabled={invalid}
        >
          만들기
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={creating}
          onClick={onCancel}
        >
          {cancelLabel}
        </Button>
      </div>
    </form>
  );
}

/** 떼기 칸이다. `single_binding` 이면 켠 채 잠그고 까닭을 적는다. */
function DetachField({
  option,
  checked,
  disabled,
  onChange,
}: {
  option: DetachOption;
  checked: boolean;
  disabled: boolean;
  onChange(checked: boolean): void;
}) {
  return (
    <div className="grid gap-1 text-sm">
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          className="accent-primary"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
        {option.label}
      </label>
      <span className="text-xs text-muted-foreground">
        {option.locked
          ? DETACH_LOCKED_REASON
          : "켜 두면 그 에이전트는 이 서비스 일을 새 에이전트에게 맡겨요."}
      </span>
    </div>
  );
}
