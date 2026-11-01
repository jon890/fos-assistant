"use client";

import { useEffect, useId, useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import { Popover } from "radix-ui";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import {
  getModelTiers,
  MODEL_TIER_LABELS,
  type ModelSelectionMode,
  type ModelTier,
  type ModelTierCode,
  type ModelTiers,
} from "@/lib/model-tiers";

/**
 * 저장한 결과다. 실패하면 단추가 이전 값으로 돌아간다.
 *
 * <p>`reported` 는 실패했지만 부르는 쪽이 이미 알렸다는 뜻이다. 빈 대화를 만들지 못하면 입력창이 알리므로
 * 이 부품은 안내를 더 띄우지 않는다. 두 안내가 겹치면 무엇이 실패했는지 읽기 어렵다.
 */
export type ModelTierSaveResult = "saved" | "failed" | "reported";

type Props = {
  agentCode: string;
  mode: ModelSelectionMode | null;
  tier: ModelTierCode | null;
  disabled: boolean;
  onChange(mode: "TIER", tier: ModelTierCode): Promise<ModelTierSaveResult>;
};

type LoadState =
  | { status: "loading" }
  | { status: "loaded"; data: ModelTiers }
  | { status: "failed" };

/**
 * 처음 위치다. 대화에 적힌 칸, 사용자 기본 칸, 그룹 기본 칸 차례다.
 *
 * <p>사용자 기본 칸은 사용자가 마지막으로 고른 칸이다. 고른 칸을 다음 새 대화에 남기는 ChatGPT 의 동작을 따른다
 * (ADR-20261101 / model-setting 의 「기본 칸」).
 */
function initialTier(
  mode: ModelSelectionMode | null,
  tier: ModelTierCode | null,
  data: ModelTiers | null,
): ModelTierCode | null {
  if (mode === "TIER" && tier !== null) return tier;
  return data?.userDefaultTier ?? data?.groupDefaultTier ?? null;
}

/**
 * 입력창 아래의 모델 단추다. 누르면 ChatGPT 의 Thinking 슬라이더처럼 칸을 고르는 조절 하나가 열린다.
 *
 * <p>관리자가 비운 칸은 보이지 않고, 칸이 하나도 없으면 단추를 그리지 않는다. 칸 뒤의 provider 와 모델, effort 는
 * 역할과 상관없이 그리지 않는다. 관리자 영역에만 그린다(ADR-063).
 *
 * <p>칸은 radio 묶음으로 그린다. 방향키로 움직이면 고른 칸이 바로 저장되고, 앞 저장이 끝나기 전에 또 고르면 마지막
 * 칸만 이어서 저장한다. 저장이 겹쳐 늦게 끝난 요청이 마지막 선택을 덮지 않게 한다.
 */
export function ModelTierPicker({
  agentCode,
  mode,
  tier,
  disabled,
  onChange,
}: Props) {
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [open, setOpen] = useState(false);
  /** 저장하는 동안 먼저 보이는 칸이다. 저장이 끝나면 비우고 대화에 적힌 칸을 다시 보인다 */
  const [draft, setDraft] = useState<ModelTierCode | null>(null);
  const [failed, setFailed] = useState(false);
  const pending = useRef<ModelTierCode | null>(null);
  const saving = useRef(false);
  const groupName = useId();

  useEffect(() => {
    let active = true;
    if (!agentCode) return;
    void getModelTiers(agentCode).then((result) => {
      if (!active) return;
      setState(
        result.ok
          ? { status: "loaded", data: result.data }
          : { status: "failed" },
      );
    });
    return () => {
      active = false;
    };
  }, [agentCode]);

  const data = state.status === "loaded" ? state.data : null;
  const tiers: ModelTier[] = data?.tiers ?? [];
  // 관리자가 칸을 하나도 채우지 않았으면 고를 것이 없다. 단추를 그리지 않고 Hermes profile 의 값으로 돈다.
  if (data !== null && tiers.length === 0) return null;

  const shown = draft ?? initialTier(mode, tier, data);
  const label = shown === null ? "모델" : MODEL_TIER_LABELS[shown];
  const ready = agentCode.length > 0 && data !== null;

  async function choose(next: ModelTierCode) {
    setDraft(next);
    setFailed(false);
    pending.current = next;
    if (saving.current) return;
    saving.current = true;
    while (pending.current !== null) {
      const target = pending.current;
      pending.current = null;
      const result = await onChange("TIER", target);
      if (result !== "saved") {
        pending.current = null;
        if (result === "failed") setFailed(true);
        break;
      }
      // 서버가 같은 트랜잭션에서 사용자 기본 칸도 바꿨다. 같은 화면에서 새 대화를 열어도 이 칸에서 시작한다.
      setState((current) =>
        current.status === "loaded"
          ? {
              status: "loaded",
              data: { ...current.data, userDefaultTier: target },
            }
          : current,
      );
    }
    saving.current = false;
    setDraft(null);
  }

  return (
    <div className="flex min-w-0 max-w-full flex-col items-start gap-1">
      <Popover.Root open={open} onOpenChange={setOpen}>
        <Popover.Trigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            data-testid="model-tier-picker"
            aria-label={`모델 단계 고르기, 지금 ${label}`}
            disabled={disabled || !ready}
            className="max-w-full min-w-0 text-xs text-muted-foreground"
          >
            <span className="truncate">{label}</span>
            <ChevronDown aria-hidden="true" />
          </Button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            side="top"
            align="start"
            sideOffset={6}
            collisionPadding={8}
            // 열리는 순간에는 아직 자리를 잡기 전이라, 기본 초점 이동이 화면을 그 자리로 스크롤한다. 스크롤 없이
            // 고른 칸에 초점을 둔다. 방향키로 바로 옮길 수 있다.
            onOpenAutoFocus={(event) => {
              event.preventDefault();
              const content = event.currentTarget as HTMLElement;
              const target =
                content.querySelector<HTMLInputElement>("input:checked") ??
                content.querySelector<HTMLInputElement>("input");
              target?.focus({ preventScroll: true });
            }}
            data-testid="model-tier-slider"
            className="z-50 w-[min(22rem,calc(100vw-1rem))] rounded-lg border bg-popover p-4 text-popover-foreground shadow-md outline-none"
          >
            <p
              id={`${groupName}-title`}
              className="text-sm font-medium text-foreground"
            >
              모델 단계
            </p>
            <p className="mt-1 text-xs text-muted-foreground">
              고른 단계는 이 대화와 다음 새 대화에 쓰여요.
            </p>
            <TierSlider
              tiers={tiers}
              shown={shown}
              disabled={disabled}
              name={groupName}
              labelledBy={`${groupName}-title`}
              onPick={(next) => void choose(next)}
            />
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      {failed ? (
        <p
          role="alert"
          data-testid="model-picker-error"
          className="px-2 text-xs text-destructive"
        >
          모델 단계를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  );
}

type SliderProps = {
  tiers: ModelTier[];
  shown: ModelTierCode | null;
  disabled: boolean;
  name: string;
  labelledBy: string;
  onPick(tier: ModelTierCode): void;
};

/**
 * 칸을 고르는 조절이다. 같은 이름의 radio 묶음이라 방향키로 움직이고, 점을 잇는 선이 고른 칸까지 채워진다.
 *
 * <p>`input type="range"` 대신 radio 를 쓴다. 칸마다 이름이 보이고, 브라우저 테스트가 칸 이름으로 바로 고른다.
 */
function TierSlider({
  tiers,
  shown,
  disabled,
  name,
  labelledBy,
  onPick,
}: SliderProps) {
  const selectedIndex = tiers.findIndex((item) => item.tier === shown);
  return (
    <div
      role="radiogroup"
      aria-labelledby={labelledBy}
      className="relative mt-4 flex"
    >
      {tiers.length > 1 ? (
        <>
          {/* 칸의 점을 잇는 선이다. 양 끝 칸의 가운데에서 멈춘다. */}
          <span
            aria-hidden="true"
            className="absolute top-[7px] h-0.5 rounded-full bg-border"
            // 칸 수가 관리자가 채운 만큼 달라져 끝점을 클래스로 정할 수 없다.
            style={{
              left: `${50 / tiers.length}%`,
              right: `${50 / tiers.length}%`,
            }}
          />
          {selectedIndex > 0 ? (
            <span
              aria-hidden="true"
              className="absolute top-[7px] h-0.5 rounded-full bg-primary"
              // 고른 칸까지 채우는 길이도 칸 수와 고른 자리에 따라 이어지는 수다.
              style={{
                left: `${50 / tiers.length}%`,
                width: `${(selectedIndex * 100) / tiers.length}%`,
              }}
            />
          ) : null}
        </>
      ) : null}
      {tiers.map((item) => (
        <label
          key={item.tier}
          className="relative flex min-w-0 flex-1 cursor-pointer flex-col items-center gap-2"
        >
          <input
            type="radio"
            name={name}
            value={item.tier}
            checked={shown === item.tier}
            disabled={disabled}
            onChange={() => onPick(item.tier)}
            data-testid={`model-tier-${item.tier.toLowerCase()}`}
            // 칸 전체를 덮는 투명한 radio 다. 점과 이름을 눌러도 radio 가 직접 받는다.
            className="peer absolute inset-0 z-10 m-0 cursor-pointer opacity-0 disabled:cursor-not-allowed"
          />
          <span
            aria-hidden="true"
            className={cn(
              "size-4 rounded-full border-2 border-border bg-background transition-colors",
              "peer-checked:border-primary peer-checked:bg-primary",
              "peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2",
            )}
          />
          <span className="truncate text-xs text-muted-foreground peer-checked:font-medium peer-checked:text-foreground">
            {item.label}
          </span>
        </label>
      ))}
    </div>
  );
}
