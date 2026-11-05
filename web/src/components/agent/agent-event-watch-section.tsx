"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { describeError } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import {
  describeEventWatchBlocker,
  describeEventWatchResult,
  fetchEventWatches,
  saveEventWatch,
  type EventWatch,
} from "@/lib/event-watch";
import { formatWhen } from "@/lib/format";

type ErrorPayload = { code?: string; message?: string };

async function errorOf(response: Response, fallback: string): Promise<string> {
  const payload = (await response.json().catch(() => ({}))) as ErrorPayload;
  return describeError(
    payload.code ?? "INTERNAL_ERROR",
    payload.message ?? fallback,
  );
}

/**
 * 먼저 살펴보기 절 끝의 「새 항목 살펴보기」 소절이다. 이 에이전트에 붙인 내 연결마다 사건 감지를 켜고 끈다.
 *
 * <p>조회가 실패해도 소절 안에 실패 문구만 그리고 절의 다른 소절을 막지 않는다.
 */
export function AgentEventWatchSection({ code }: { code: string }) {
  const [watches, setWatches] = useState<EventWatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchEventWatches(code)
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(
            await errorOf(response, "새 항목 살펴보기 설정을 읽지 못했어요."),
          );
        }
        return response.json() as Promise<EventWatch[]>;
      })
      .then((loaded) => {
        if (!cancelled) setWatches(loaded);
      })
      .catch((caught: unknown) => {
        if (cancelled) return;
        setError(
          caught instanceof Error
            ? caught.message
            : "새 항목 살펴보기 설정을 읽지 못했어요. 잠시 뒤 다시 열어 주세요.",
        );
      });
    return () => {
      cancelled = true;
    };
  }, [code]);

  function replace(saved: EventWatch) {
    setWatches((current) =>
      current === null
        ? current
        : current.map((each) =>
            each.connectorId === saved.connectorId ? saved : each,
          ),
    );
  }

  return (
    <section
      aria-label="새 항목 살펴보기"
      className="mt-6 border-t border-border pt-4"
    >
      <h3 className="font-medium">새 항목 살펴보기</h3>
      <p className="mt-1 text-sm text-muted-foreground">
        붙인 서비스에 새 항목이 생기면 15분 안에 살펴보고 알릴 것만 알려요. 밤
        10시부터 아침 7시까지는 아침에 한 번에 알려요.
      </p>
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : watches === null ? (
        <p className="mt-3 text-sm text-muted-foreground">
          새 항목 살펴보기 설정을 불러오는 중이에요.
        </p>
      ) : watches.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground">
          이 에이전트에 붙인 연결 가운데 새 항목 살펴보기를 지원하는 것이
          없어요.
        </p>
      ) : (
        <ul className="mt-4 flex flex-col gap-6">
          {watches.map((watch) => (
            <li key={watch.connectorId} data-testid="event-watch">
              <EventWatchItem code={code} watch={watch} onSaved={replace} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function EventWatchItem({
  code,
  watch,
  onSaved,
}: {
  code: string;
  watch: EventWatch;
  onSaved: (saved: EventWatch) => void;
}) {
  const [enabled, setEnabled] = useState(watch.enabled);
  const [timezone, setTimezone] = useState(watch.timezone);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const label = `${watch.title} 새 ${watch.noun} 살펴보기`;
  const idPrefix = `event-watch-${watch.connectorId}`;

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setError(null);
    try {
      const response = await saveEventWatch(code, watch.connectorId, {
        enabled,
        timezone: timezone.trim(),
      });
      if (!response.ok) {
        setError(await errorOf(response, `${label} 설정을 저장하지 못했어요.`));
        return;
      }
      const saved = (await response.json()) as EventWatch;
      onSaved(saved);
      setEnabled(saved.enabled);
      setTimezone(saved.timezone);
    } catch {
      setError(
        `${label} 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.`,
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={(event) => void save(event)}
      className="flex flex-col gap-3"
    >
      <div className="flex items-center justify-between gap-4">
        <Label htmlFor={`${idPrefix}-enabled`}>{label}</Label>
        <Switch
          id={`${idPrefix}-enabled`}
          aria-label={label}
          checked={enabled}
          disabled={!watch.available && !watch.enabled}
          onCheckedChange={setEnabled}
        />
      </div>
      <div className="flex flex-col gap-1.5 sm:max-w-xs">
        <Label htmlFor={`${idPrefix}-timezone`}>시간대</Label>
        <Input
          id={`${idPrefix}-timezone`}
          value={timezone}
          disabled={!watch.available && !watch.enabled}
          onChange={(event) => setTimezone(event.target.value)}
        />
      </div>
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      {watch.failing ? (
        <Notice variant="warning">
          {watch.title} 다시 연결이 필요해요.{" "}
          <Link
            prefetch={false}
            href="/connections"
            className="underline underline-offset-4"
          >
            외부 서비스 연결
          </Link>
          에서 다시 연결해 주세요.
        </Notice>
      ) : null}
      {watch.dailyLimitReached ? (
        <Notice variant="info">
          오늘 상한 도달. 내일 첫 확인에서 쌓인 {watch.noun}도 함께 살펴봐요.
        </Notice>
      ) : null}
      {watch.blockers.length > 0 ? (
        <ul className="flex flex-col gap-2">
          {watch.blockers.map((blocker) => (
            <li key={blocker.code}>
              <Notice variant="info">
                {describeEventWatchBlocker(blocker, watch)}
              </Notice>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="submit"
          loading={saving}
          loadingText="저장하는 중"
          disabled={!watch.available && !watch.enabled}
        >
          저장
        </Button>
        {watch.enabled ? (
          <p className="text-sm text-muted-foreground">
            {watch.lastCheckedAt
              ? `마지막 확인 ${formatWhen(watch.lastCheckedAt)}, ${describeEventWatchResult(watch)}`
              : describeEventWatchResult(watch)}
          </p>
        ) : null}
      </div>
    </form>
  );
}
