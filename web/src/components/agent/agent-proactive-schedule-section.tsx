"use client";

import { useEffect, useState, type FormEvent } from "react";
import { describeError } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { formatWhen } from "@/lib/format";
import {
  describeLastCheck,
  describeScheduleBlocker,
  fetchProactiveCheckSchedule,
  saveProactiveCheckSchedule,
  type ProactiveCheckSchedule,
} from "@/lib/proactive-check";

type ErrorPayload = { code?: string; message?: string };

/** 에이전트 상세의 매일 깨우기 설정을 읽고 저장한다. */
export function AgentProactiveScheduleSection({ code }: { code: string }) {
  const [schedule, setSchedule] = useState<ProactiveCheckSchedule | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [time, setTime] = useState("09:00");
  const [timezone, setTimezone] = useState("Asia/Seoul");

  useEffect(() => {
    let cancelled = false;
    fetchProactiveCheckSchedule(code)
      .then(async (response) => {
        if (!response.ok) {
          const payload = (await response
            .json()
            .catch(() => ({}))) as ErrorPayload;
          throw new Error(
            describeError(
              payload.code ?? "INTERNAL_ERROR",
              payload.message ?? "설정을 읽지 못했어요.",
            ),
          );
        }
        return response.json() as Promise<ProactiveCheckSchedule>;
      })
      .then((loaded) => {
        if (cancelled) return;
        setSchedule(loaded);
        setTime(loaded.time);
        setTimezone(loaded.timezone);
      })
      .catch((caught: unknown) => {
        if (cancelled) return;
        setError(
          caught instanceof Error
            ? caught.message
            : "설정을 읽지 못했어요. 잠시 뒤 다시 열어 주세요.",
        );
      });
    return () => {
      cancelled = true;
    };
  }, [code]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (schedule === null || saving) return;

    setSaving(true);
    setError(null);
    try {
      const response = await saveProactiveCheckSchedule(code, {
        enabled: schedule.enabled,
        time,
        timezone: timezone.trim(),
      });
      if (!response.ok) {
        const payload = (await response
          .json()
          .catch(() => ({}))) as ErrorPayload;
        setError(
          describeError(
            payload.code ?? "INTERNAL_ERROR",
            payload.message ?? "매일 깨우기 설정을 저장하지 못했어요.",
          ),
        );
        return;
      }
      const saved = (await response.json()) as ProactiveCheckSchedule;
      setSchedule(saved);
      setTime(saved.time);
      setTimezone(saved.timezone);
    } catch {
      setError(
        "매일 깨우기 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="mt-6 border-t border-border pt-4">
      <h3 className="font-medium">매일 깨우기</h3>
      <p className="mt-1 text-sm text-muted-foreground">
        정한 시각에 새로 알릴 것이 있는지 살펴봐요.
      </p>
      {error ? (
        <Notice variant="error" role="alert" className="mt-3">
          {error}
        </Notice>
      ) : null}
      {schedule === null ? (
        <p className="mt-3 text-sm text-muted-foreground">
          매일 깨우기 설정을 불러오는 중이에요.
        </p>
      ) : (
        <form
          onSubmit={(event) => void save(event)}
          className="mt-4 flex flex-col gap-4"
        >
          <div className="flex items-center justify-between gap-4">
            <Label htmlFor="proactive-check-schedule-enabled">
              매일 깨우기 사용
            </Label>
            <Switch
              id="proactive-check-schedule-enabled"
              aria-label="매일 깨우기 사용"
              checked={schedule.enabled}
              disabled={!schedule.schedulingAvailable && !schedule.enabled}
              onCheckedChange={(enabled) =>
                setSchedule((current) =>
                  current === null ? current : { ...current, enabled },
                )
              }
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="proactive-check-schedule-time">시각</Label>
              <Input
                id="proactive-check-schedule-time"
                type="time"
                required
                value={time}
                disabled={!schedule.schedulingAvailable}
                onChange={(event) => setTime(event.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="proactive-check-schedule-timezone">시간대</Label>
              <Input
                id="proactive-check-schedule-timezone"
                required
                value={timezone}
                disabled={!schedule.schedulingAvailable}
                onChange={(event) => setTimezone(event.target.value)}
              />
            </div>
          </div>
          <ScheduleBlockers blockers={schedule.blockers} />
          <div className="flex flex-wrap items-center gap-3">
            <Button
              type="submit"
              loading={saving}
              loadingText="저장하는 중"
              disabled={!schedule.schedulingAvailable && schedule.enabled}
            >
              저장
            </Button>
            {schedule.nextRunAt ? (
              <p className="text-sm text-muted-foreground">
                다음 실행 {formatWhen(schedule.nextRunAt)}
              </p>
            ) : null}
          </div>
          <LastCheckResult lastCheck={schedule.lastCheck} />
        </form>
      )}
    </section>
  );
}

function ScheduleBlockers({
  blockers,
}: {
  blockers: ProactiveCheckSchedule["blockers"];
}) {
  if (blockers.length === 0) return null;
  return (
    <ul className="flex flex-col gap-2">
      {blockers.map((blocker) => (
        <li key={blocker.code}>
          <Notice variant="info">{describeScheduleBlocker(blocker)}</Notice>
        </li>
      ))}
    </ul>
  );
}

function LastCheckResult({
  lastCheck,
}: {
  lastCheck: ProactiveCheckSchedule["lastCheck"];
}) {
  if (lastCheck === null) {
    return (
      <p className="text-sm text-muted-foreground">
        아직 매일 깨우기 결과가 없어요.
      </p>
    );
  }
  return (
    <p className="text-sm text-muted-foreground">
      마지막 결과 {formatWhen(lastCheck.finishedAt ?? lastCheck.startedAt)},{" "}
      {describeLastCheck(lastCheck)}
    </p>
  );
}
