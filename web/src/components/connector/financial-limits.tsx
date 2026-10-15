"use client";

import { useEffect, useState } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import {
  exceedsMaximum,
  LIMIT_VALUE_PATTERN,
  readFinancialLimits,
  saveFinancialLimit,
  type FinancialLimitItem,
  type FinancialLimits,
} from "@/lib/financial-limits";

type Pending = { item: FinancialLimitItem; value: string | null };

function unit(item: FinancialLimitItem) {
  return item.currency ? ` ${item.currency}` : "주";
}

/** 저장할 수 없는 입력의 까닭이다. 저장할 수 있으면 null 이다. */
function invalidReason(item: FinancialLimitItem, draft: string): string | null {
  if (!LIMIT_VALUE_PATTERN.test(draft)) return "0 이상의 숫자로 적어 주세요.";
  if (exceedsMaximum(draft, item.maximum))
    return `최대치 ${item.maximum}${unit(item)} 보다 크게 정할 수 없어요.`;
  return null;
}

/** 상한 한 줄이다. 값을 고쳐 저장하거나 최대치로 되돌린다. 저장은 부른 쪽이 확인 창을 띄운 뒤 한다. */
function LimitRow({
  item,
  disabled,
  onSave,
}: {
  item: FinancialLimitItem;
  disabled: boolean;
  onSave(value: string | null): void;
}) {
  const [draft, setDraft] = useState(item.value);
  const reason = invalidReason(item, draft);
  return (
    <li className="space-y-1" data-testid={`financial-limit-${item.name}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-28 text-sm">{item.label}</span>
        <Input
          aria-label={`${item.label} 상한`}
          className="w-36"
          inputMode="decimal"
          value={draft}
          onChange={(event) => setDraft(event.target.value.trim())}
        />
        <span className="text-xs text-muted-foreground">
          {item.currency ?? "주"} · 최대 {item.maximum}
          {unit(item)} · {item.custom ? "직접 정함" : "최대치를 따름"}
        </span>
        <Button
          size="sm"
          variant="outline"
          disabled={disabled || reason !== null || draft === item.value}
          onClick={() => onSave(draft)}
        >
          저장
        </Button>
        {item.custom ? (
          <Button
            size="sm"
            variant="ghost"
            disabled={disabled}
            onClick={() => onSave(null)}
          >
            최대치로
          </Button>
        ) : null}
      </div>
      {reason && draft !== item.value ? (
        <p className="text-xs text-destructive">{reason}</p>
      ) : null}
    </li>
  );
}

/**
 * 금융 연결의 주문 상한 절이다(ADR-20261015 / financial-user-limits). 지금 값과 운영 최대치, 오늘 쓴 금액과 남은
 * 한도를 보인다. 계좌 주인의 직접 조작이라 승인 카드 없이 반영하되, 저장 전에 한 번 더 묻는다. 이 사용자에게 열린
 * 계좌가 없으면 그리지 않는다.
 */
export function FinancialLimitsSection() {
  const [limits, setLimits] = useState<FinancialLimits | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void readFinancialLimits().then((result) => {
      if (result.ok) setLimits(result.data);
      else setError(result.message);
    });
  }, []);

  if (!limits?.available)
    return error ? <Notice variant="error">{error}</Notice> : null;

  async function confirm() {
    if (!pending) return;
    setBusy(true);
    const result = await saveFinancialLimit(pending.item.name, pending.value);
    setBusy(false);
    setPending(null);
    if (result.ok) {
      setLimits(result.data);
      setError(null);
    } else setError(result.message);
  }

  return (
    <section className="space-y-2" data-testid="financial-limits">
      <h2 className="text-sm font-semibold">주문 상한</h2>
      <p className="text-xs text-muted-foreground">
        오늘 쓴 금액 {limits.spentToday.KRW} KRW · {limits.spentToday.USD} USD,
        오늘 남은 한도 {limits.remainingToday.KRW} KRW ·{" "}
        {limits.remainingToday.USD} USD
      </p>
      <ul className="space-y-2">
        {limits.limits.map((item) => (
          <LimitRow
            key={`${item.name}:${item.value}`}
            item={item}
            disabled={busy}
            onSave={(value) => setPending({ item, value })}
          />
        ))}
      </ul>
      {error ? <Notice variant="error">{error}</Notice> : null}
      {pending ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open && !busy) setPending(null);
          }}
        >
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>주문 상한을 바꿀까요?</AlertDialogTitle>
              <AlertDialogDescription>
                {pending.item.label} 상한을{" "}
                {pending.value === null
                  ? `최대치 ${pending.item.maximum}${unit(pending.item)}`
                  : `${pending.value}${unit(pending.item)}`}
                (으)로 바꿔요. 저장하면 바로 다음 주문부터 적용돼요.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel asChild>
                <Button variant="outline" disabled={busy}>
                  취소
                </Button>
              </AlertDialogCancel>
              <Button
                loading={busy}
                loadingText="바꾸는 중"
                onClick={() => void confirm()}
              >
                바꾸기
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      ) : null}
    </section>
  );
}
