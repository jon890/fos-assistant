"use client";

import { useState } from "react";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { describeError } from "@/components/error-message";
import { saveAgentIntroduction } from "@/lib/agent-api";
import {
  AGENT_COLORS,
  AGENT_DESCRIPTION_MAX_CHARS,
  AGENT_ICONS,
  AGENT_NAME_MAX_CHARS,
  asAgentColor,
  badgeColorClass,
  capabilitiesLine,
  initialOf,
  type AgentColor,
} from "@/lib/agent-badge";
import type { AgentCapabilities } from "@/lib/agent";
import { AgentBadge } from "./agent-badge";

/** 상세 「소개」 절이 처음 받는 값이다. 일반 목록이나 관리자 목록의 줄에서 온다. */
export type AgentIntroductionValue = {
  name: string;
  description?: string | null;
  icon?: string | null;
  color?: string | null;
  capabilities?: AgentCapabilities;
};

/** 색 고르기 단추에 붙이는 읽기 이름이다 */
const COLOR_LABELS: Record<AgentColor, string> = {
  VIOLET: "보라",
  BLUE: "파랑",
  TEAL: "청록",
  GREEN: "초록",
  AMBER: "노랑",
  ORANGE: "주황",
  ROSE: "분홍",
  SLATE: "회색",
};

type ErrorPayload = { code: string; message: string };

/**
 * 에이전트의 이름, 한 줄 소개, 아이콘, 색을 고치는 절이다(docs/features/agent-team.md 「소개와 할 수 있는 일」).
 * 고칠 수 없는 사람에게는 읽기만 보인다. 할 수 있는 일은 붙인 서비스와 스킬에서 모으므로 여기서 고치지 않는다.
 */
export function AgentIntroductionSection({
  code,
  initial,
  editable,
  onSaved,
}: {
  code: string;
  initial: AgentIntroductionValue;
  editable: boolean;
  onSaved?(name: string): void;
}) {
  const [name, setName] = useState(initial.name);
  const [description, setDescription] = useState(initial.description ?? "");
  const [icon, setIcon] = useState<string | null>(initial.icon ?? null);
  const [color, setColor] = useState<AgentColor | null>(
    asAgentColor(initial.color),
  );
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const trimmedName = name.trim();
  const descriptionLength = description.trim().length;
  const invalid =
    trimmedName.length === 0 ||
    trimmedName.length > AGENT_NAME_MAX_CHARS ||
    descriptionLength > AGENT_DESCRIPTION_MAX_CHARS;
  const capabilities = capabilitiesLine(initial.capabilities);

  function touched() {
    setSaved(false);
    setError(null);
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const response = await saveAgentIntroduction(code, {
        name: trimmedName,
        description: description.trim() || null,
        icon,
        color,
      });
      if (!response.ok) {
        const failure = (await response.json()) as ErrorPayload;
        setError(describeError(failure.code, failure.message));
        return;
      }
      setSaved(true);
      onSaved?.(trimmedName);
    } catch {
      setError(describeError("INTERNAL_ERROR", "저장하지 못했어요."));
    } finally {
      setSaving(false);
    }
  }

  const preview = { name: trimmedName || initial.name, code, icon, color };

  return (
    <section
      aria-label="소개"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">소개</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        대화의 답과 에이전트 목록에 보이는 이름표예요. 다른 에이전트가 일을 맡길
        때도 이 소개를 봐요.
      </p>
      <div className="mt-4 flex items-center gap-3">
        <AgentBadge agent={preview} />
        <div className="min-w-0">
          <p className="truncate text-sm font-medium">{preview.name}</p>
          <p className="truncate text-xs text-muted-foreground">
            {description.trim() || capabilities}
          </p>
        </div>
      </div>
      {capabilities ? (
        <p className="mt-3 text-sm">
          <span className="text-muted-foreground">할 수 있는 일 </span>
          {capabilities}
        </p>
      ) : null}
      {editable ? (
        <div className="mt-4 grid gap-4">
          <label className="grid gap-1 text-sm">
            이름
            <Input
              value={name}
              maxLength={AGENT_NAME_MAX_CHARS}
              onChange={(event) => {
                setName(event.target.value);
                touched();
              }}
            />
          </label>
          <label className="grid gap-1 text-sm">
            한 줄 소개
            <Input
              value={description}
              placeholder="예: 쇼핑 검색과 장바구니를 맡아요"
              onChange={(event) => {
                setDescription(event.target.value);
                touched();
              }}
            />
            <span className="text-xs text-muted-foreground">
              {descriptionLength <= AGENT_DESCRIPTION_MAX_CHARS
                ? `${AGENT_DESCRIPTION_MAX_CHARS - descriptionLength}자 더 쓸 수 있어요`
                : `${descriptionLength - AGENT_DESCRIPTION_MAX_CHARS}자 넘게 썼어요`}
            </span>
          </label>
          <IntroductionPickers
            code={code}
            initialLetter={initialOf(preview.name)}
            icon={icon}
            color={color}
            onIcon={(value) => {
              setIcon(value);
              touched();
            }}
            onColor={(value) => {
              setColor(value);
              touched();
            }}
          />
          {error ? (
            <Notice variant="error" role="alert" className="break-all">
              {error}
            </Notice>
          ) : null}
          {saved ? <p className="text-sm">저장했어요.</p> : null}
          <div>
            <Button
              onClick={() => void save()}
              disabled={invalid}
              loading={saving}
              loadingText="저장 중"
            >
              소개 저장
            </Button>
          </div>
        </div>
      ) : null}
    </section>
  );
}

/** 아이콘과 색 고르기다. 고르지 않으면 이름 첫 글자와 자동 색으로 그린다 */
function IntroductionPickers({
  code,
  initialLetter,
  icon,
  color,
  onIcon,
  onColor,
}: {
  code: string;
  initialLetter: string;
  icon: string | null;
  color: AgentColor | null;
  onIcon(value: string | null): void;
  onColor(value: AgentColor | null): void;
}) {
  return (
    <>
      <fieldset>
        <legend className="text-sm">아이콘</legend>
        <div
          role="group"
          aria-label="아이콘"
          className="mt-1 flex flex-wrap gap-1"
        >
          <Choice
            label="이름 첫 글자"
            checked={icon === null}
            onSelect={() => {
              onIcon(null);
            }}
          >
            {initialLetter}
          </Choice>
          {AGENT_ICONS.map((value) => (
            <Choice
              key={value}
              label={value}
              checked={icon === value}
              onSelect={() => {
                onIcon(value);
              }}
            >
              {value}
            </Choice>
          ))}
        </div>
      </fieldset>
      <fieldset>
        <legend className="text-sm">색</legend>
        <div role="group" aria-label="색" className="mt-1 flex flex-wrap gap-1">
          <Choice
            label="자동"
            checked={color === null}
            onSelect={() => {
              onColor(null);
            }}
          >
            <span
              className={cn("size-5 rounded-full", badgeColorClass(null, code))}
            />
          </Choice>
          {AGENT_COLORS.map((value) => (
            <Choice
              key={value}
              label={COLOR_LABELS[value]}
              checked={color === value}
              onSelect={() => {
                onColor(value);
              }}
            >
              <span
                className={cn(
                  "size-5 rounded-full",
                  badgeColorClass(value, code),
                )}
              />
            </Choice>
          ))}
        </div>
      </fieldset>
    </>
  );
}

/** 아이콘과 색 고르기의 단추 하나다. 고른 것은 테두리로 보인다 */
function Choice({
  label,
  checked,
  onSelect,
  children,
}: {
  label: string;
  checked: boolean;
  onSelect(): void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={checked}
      aria-label={label}
      onClick={onSelect}
      className={cn(
        "flex size-9 items-center justify-center rounded-md border text-base",
        checked
          ? "border-primary bg-primary-soft"
          : "border-border hover:bg-muted",
      )}
    >
      {children}
    </button>
  );
}
