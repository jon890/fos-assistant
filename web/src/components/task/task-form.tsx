"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { describeFailure } from "@/components/error-message";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Notice } from "@/components/ui/notice";
import { Textarea } from "@/components/ui/textarea";
import { fetchChatAgents } from "@/lib/chat-api";
import { createTask, updateTask } from "@/lib/task-api";
import {
  choiceOf,
  scheduleRequestOf,
  WEEKDAY_LABELS,
  type ConversationMode,
  type MissedPolicy,
  type NotifyPolicy,
  type ScheduleChoice,
  type TaskRequest,
  type TaskView,
} from "@/lib/task";

const DEFAULT_TIME_ZONE = "Asia/Seoul";
const INSTRUCTION_MAX = 8000;

type AgentOption = { code: string; name: string; runsTasks?: boolean };

const KIND_LABELS: Record<ScheduleChoice["kind"], string> = {
  daily: "매일",
  weekly: "매주",
  monthly: "매달",
  once: "한 번",
  cron: "직접 입력",
};

/** 입력칸과 이름표를 묶는다. */
function Field({
  id,
  label,
  children,
}: {
  id: string;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  );
}

/**
 * 예약 작업을 만들거나 고치는 폼이다. `task` 가 null 이면 새 작업이다.
 *
 * <p>만들면 그 작업 화면으로 가고, 고치면 `onSaved` 로 저장된 작업을 넘긴다.
 */
export function TaskForm({
  task,
  onSaved,
}: {
  task: TaskView | null;
  onSaved?: (saved: TaskView) => void;
}) {
  const router = useRouter();
  const initial = task ? choiceOf(task.schedule) : null;
  const [title, setTitle] = useState(task?.title ?? "");
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [agentCode, setAgentCode] = useState(task?.agentCode ?? "");
  const [instruction, setInstruction] = useState(task?.instruction ?? "");
  const [kind, setKind] = useState<ScheduleChoice["kind"]>(
    initial?.kind ?? "daily",
  );
  const [time, setTime] = useState(
    initial && "time" in initial ? initial.time : "09:00",
  );
  const [weekday, setWeekday] = useState(
    initial?.kind === "weekly" ? initial.weekday : 1,
  );
  const [day, setDay] = useState(initial?.kind === "monthly" ? initial.day : 1);
  const [date, setDate] = useState(
    initial?.kind === "once" ? initial.date : "",
  );
  const [cron, setCron] = useState(
    initial?.kind === "cron" ? initial.cron : "",
  );
  const [timeZone, setTimeZone] = useState(
    task?.schedule.timeZone ?? DEFAULT_TIME_ZONE,
  );
  const [conversationMode, setConversationMode] = useState<ConversationMode>(
    task?.conversationMode ?? "NEW_PER_RUN",
  );
  const [missedPolicy, setMissedPolicy] = useState<MissedPolicy>(
    task?.missedPolicy ?? "RUN_ONCE",
  );
  const [notify, setNotify] = useState<NotifyPolicy>(task?.notify ?? "ALWAYS");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchChatAgents()
      .then((response) => (response.ok ? response.json() : []))
      .then((data: AgentOption[]) => {
        if (cancelled) return;
        const taskAgents = data.filter((agent) => agent.runsTasks !== false);
        setAgents(taskAgents);
        setAgentCode((current) => current || taskAgents[0]?.code || "");
      })
      .catch(() => {
        if (!cancelled) setAgents([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 지금 작업의 에이전트가 목록에 없어도(이름이 바뀌었거나 읽지 못했을 때) 고른 값이 사라지지 않게 한다.
  const options =
    task?.agentCode && !agents.some((agent) => agent.code === task.agentCode)
      ? [
          { code: task.agentCode, name: task.agentName ?? task.agentCode },
          ...agents,
        ]
      : agents;

  function choice(): ScheduleChoice {
    switch (kind) {
      case "daily":
        return { kind, time };
      case "weekly":
        return { kind, weekday, time };
      case "monthly":
        return { kind, day, time };
      case "once":
        return { kind, date, time };
      case "cron":
        return { kind, cron };
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;
    const body: TaskRequest = {
      title: title.trim(),
      agentCode,
      instruction,
      schedule: scheduleRequestOf(choice(), timeZone.trim()),
      conversationMode,
      missedPolicy,
      notify,
    };
    setSaving(true);
    setError(null);
    try {
      const response = task
        ? await updateTask(task.id, body)
        : await createTask(body);
      if (!response.ok) {
        setError(await describeFailure(response));
        return;
      }
      const saved = (await response.json()) as TaskView;
      if (task) onSaved?.(saved);
      else router.push(`/tasks/${saved.id}`);
    } catch {
      setError("작업을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={(event) => void submit(event)}
      className="flex flex-col gap-4"
    >
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      <Field id="task-title" label="이름">
        <Input
          id="task-title"
          required
          maxLength={100}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
      </Field>
      <Field id="task-agent" label="에이전트">
        <NativeSelect
          id="task-agent"
          required
          value={agentCode}
          onChange={(event) => setAgentCode(event.target.value)}
        >
          {options.map((agent) => (
            <option key={agent.code} value={agent.code}>
              {agent.name}
            </option>
          ))}
        </NativeSelect>
      </Field>
      <Field id="task-instruction" label="지시">
        <Textarea
          id="task-instruction"
          required
          maxLength={INSTRUCTION_MAX}
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
        />
      </Field>
      <Field id="task-kind" label="시각">
        <NativeSelect
          id="task-kind"
          value={kind}
          onChange={(event) =>
            setKind(event.target.value as ScheduleChoice["kind"])
          }
        >
          {(Object.keys(KIND_LABELS) as ScheduleChoice["kind"][]).map(
            (value) => (
              <option key={value} value={value}>
                {KIND_LABELS[value]}
              </option>
            ),
          )}
        </NativeSelect>
      </Field>
      {kind === "weekly" ? (
        <Field id="task-weekday" label="요일">
          <NativeSelect
            id="task-weekday"
            value={weekday}
            onChange={(event) => setWeekday(Number(event.target.value))}
          >
            {WEEKDAY_LABELS.map((label, index) => (
              <option key={label} value={index}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </Field>
      ) : null}
      {kind === "monthly" ? (
        <Field id="task-day" label="날짜(일)">
          <Input
            id="task-day"
            type="number"
            required
            min={1}
            max={31}
            value={day}
            onChange={(event) => setDay(Number(event.target.value))}
          />
        </Field>
      ) : null}
      {kind === "once" ? (
        <Field id="task-date" label="날짜">
          <Input
            id="task-date"
            type="date"
            required
            value={date}
            onChange={(event) => setDate(event.target.value)}
          />
        </Field>
      ) : null}
      {kind === "cron" ? (
        <Field id="task-cron" label="cron 식">
          <Input
            id="task-cron"
            required
            maxLength={100}
            placeholder="0 9 * * 1"
            value={cron}
            onChange={(event) => setCron(event.target.value)}
          />
        </Field>
      ) : (
        <Field id="task-time" label="시각(시:분)">
          <Input
            id="task-time"
            type="time"
            required
            value={time}
            onChange={(event) => setTime(event.target.value)}
          />
        </Field>
      )}
      <Field id="task-time-zone" label="시간대">
        <Input
          id="task-time-zone"
          required
          value={timeZone}
          onChange={(event) => setTimeZone(event.target.value)}
        />
      </Field>
      <Field id="task-conversation-mode" label="대화 방식">
        <NativeSelect
          id="task-conversation-mode"
          value={conversationMode}
          onChange={(event) =>
            setConversationMode(event.target.value as ConversationMode)
          }
        >
          <option value="NEW_PER_RUN">실행마다 새 대화</option>
          <option value="SINGLE">대화 하나에 이어서</option>
        </NativeSelect>
      </Field>
      <Field id="task-missed-policy" label="놓친 실행">
        <NativeSelect
          id="task-missed-policy"
          value={missedPolicy}
          onChange={(event) =>
            setMissedPolicy(event.target.value as MissedPolicy)
          }
        >
          <option value="RUN_ONCE">한 번만 실행</option>
          <option value="SKIP">건너뛰기</option>
        </NativeSelect>
      </Field>
      <Field id="task-notify" label="알림">
        <NativeSelect
          id="task-notify"
          value={notify}
          onChange={(event) => setNotify(event.target.value as NotifyPolicy)}
        >
          <option value="ALWAYS">늘 알림</option>
          <option value="ON_FAILURE">실패만 알림</option>
          <option value="NEVER">알리지 않음</option>
        </NativeSelect>
      </Field>
      <div>
        <Button type="submit" loading={saving} loadingText="저장하는 중">
          저장
        </Button>
      </div>
    </form>
  );
}
