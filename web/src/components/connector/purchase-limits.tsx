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
  parsePurchaseLimitInput,
  readPurchaseLimits,
  savePurchaseLimit,
  type PurchaseLimitName,
  type PurchaseLimits,
} from "@/lib/purchase-limits";

const LABELS: Record<PurchaseLimitName, string> = {
  order: "한 건 구매 상한",
  daily: "하루 구매 상한",
};

type Pending = { name: PurchaseLimitName; value: number };

const won = (value: number) => `${value.toLocaleString("ko-KR")}원`;

/** 상한 한 줄이다. 값을 고쳐 저장한다. 저장은 부른 쪽이 확인 창을 띄운 뒤 한다. */
function LimitRow({
  name,
  value,
  disabled,
  onSave,
}: {
  name: PurchaseLimitName;
  value: number;
  disabled: boolean;
  onSave(value: number): void;
}) {
  const [draft, setDraft] = useState(String(value));
  const parsed = parsePurchaseLimitInput(draft);
  return (
    <li className="space-y-1" data-testid={`purchase-limit-${name}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="min-w-28 text-sm">{LABELS[name]}</span>
        <Input
          aria-label={LABELS[name]}
          className="w-36"
          inputMode="numeric"
          value={draft}
          onChange={(event) => setDraft(event.target.value.trim())}
        />
        <span className="text-xs text-muted-foreground">원</span>
        <Button
          size="sm"
          variant="outline"
          disabled={disabled || parsed === null || parsed === value}
          onClick={() => parsed !== null && onSave(parsed)}
        >
          저장
        </Button>
      </div>
      {parsed === null ? (
        <p className="text-xs text-destructive">
          0 이상 1조 미만의 원 단위 숫자로 적어 주세요.
        </p>
      ) : null}
    </li>
  );
}

/**
 * 구매 도구를 쓰는 연결의 구매 상한 절이다(ADR-20261113 / purchase-checkout). 한 건 상한과 하루 상한, 오늘 승인한
 * 합계와 남은 한도를 보인다. 기본 0 이면 주문서를 준비하지 않는다. 사용자의 직접 조작이라 승인 카드 없이 반영하되,
 * 저장 전에 한 번 더 묻는다. 운영 최대치는 없다.
 */
export function PurchaseLimitsSection() {
  const [limits, setLimits] = useState<PurchaseLimits | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void readPurchaseLimits().then((result) => {
      if (result.ok) setLimits(result.data);
      else setError(result.message);
    });
  }, []);

  if (!limits) return error ? <Notice variant="error">{error}</Notice> : null;

  async function confirm() {
    if (!pending) return;
    setBusy(true);
    const result = await savePurchaseLimit(pending.name, pending.value);
    setBusy(false);
    setPending(null);
    if (result.ok) {
      setLimits(result.data);
      setError(null);
    } else setError(result.message);
  }

  const closed = limits.orderKrw === 0 || limits.dailyKrw === 0;
  return (
    <section className="space-y-2" data-testid="purchase-limits">
      <h2 className="text-sm font-semibold">구매 상한</h2>
      <p className="text-xs text-muted-foreground">
        오늘 승인한 금액 {won(limits.spentTodayKrw)}, 오늘 남은 한도{" "}
        {won(limits.remainingTodayKrw)}
      </p>
      {closed ? (
        <p className="text-xs text-muted-foreground">
          두 상한이 모두 0 보다 커야 에이전트가 주문서를 준비할 수 있어요.
        </p>
      ) : null}
      <ul className="space-y-2">
        {(["order", "daily"] as const).map((name) => {
          const value = name === "order" ? limits.orderKrw : limits.dailyKrw;
          return (
            <LimitRow
              key={`${name}:${value}`}
              name={name}
              value={value}
              disabled={busy}
              onSave={(next) => setPending({ name, value: next })}
            />
          );
        })}
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
              <AlertDialogTitle>구매 상한을 바꿀까요?</AlertDialogTitle>
              <AlertDialogDescription>
                {LABELS[pending.name]}을 {won(pending.value)}(으)로 바꿔요.
                저장하면 바로 다음 주문서 준비부터 적용돼요.
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
