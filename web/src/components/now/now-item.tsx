"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Fragment, useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  itemActions,
  itemHref,
  originText,
  reasonText,
  type AttentionCardKey,
  type AttentionItem,
  type ItemAction,
} from "@/lib/attention";
import { recordAttentionEvent } from "@/lib/attention-api";
import {
  reactToDecision,
  type DecisionReaction,
} from "@/lib/decision-reaction-api";
import {
  executionStatusLabel,
  executionStatusVariant,
} from "@/lib/execution-status";
import {
  acceptFollowUp,
  dropFollowUp,
  finishFollowUp,
  rejectFollowUp,
  type FollowUpView,
} from "@/lib/follow-up-api";
import type { MemoryApiResult } from "@/lib/memory-api";
import { formatFullTime, formatRelative } from "@/lib/format";
import { FollowUpDialog } from "./follow-up-dialog";
import {
  ControlledRow,
  NowItemControls,
  type ItemControl,
} from "./now-item-controls";

/** 할 일의 상태를 바꾸는 단추와 그 요청이다. 「고치기」 는 대화 상자를 열어 따로 다룬다. */
const FOLLOW_UP_ACTIONS: Partial<
  Record<
    ItemAction["kind"],
    (id: string) => Promise<MemoryApiResult<FollowUpView>>
  >
> = {
  accept: acceptFollowUp,
  reject: rejectFollowUp,
  done: finishFollowUp,
  drop: dropFollowUp,
};

/** 먼저 다룰 문제에 반응을 남기는 단추와 그 반응이다. 할 일 API 가 아니라 판정 API 로 간다. */
const DECISION_REACTIONS: Partial<
  Record<ItemAction["kind"], DecisionReaction>
> = {
  "react-accept": "ACCEPTED",
  "react-dismiss": "DISMISSED",
};

/**
 * 지금 화면의 항목 한 줄이다. 제목은 모델이 쓴 글일 수 있어 평문으로만 그린다(ADR-009).
 *
 * <p>할 일의 식별자는 응답의 `followUp.id` 에서 읽는다. `itemKey` 와 `stateKey` 는 제어와 사건에 그대로 돌려보낸다.
 * 할 일을 바꾸면 사건을 남긴 뒤 서버 부품을 다시 읽는다. 숨기기와 미루기는 다시 읽지 않고 이 줄만 바꿔 그린다.
 *
 * @param card 이 항목이 있는 카드. 제어는 카드마다 따로 걸린다
 * @param readAt 응답을 읽은 시각. 서버에서 그린 글과 브라우저에서 다시 그린 글이 같도록 지금 시각 대신 쓴다
 * @param control 이 항목에 건 제어. 카드가 열쇠별로 갖고 내려 준다. 없으면 `null`
 * @param onControlChange 숨기거나 미뤘으면 그 제어로, 되돌렸으면 `null` 로 부른다. 카드가 제어를 한 곳에 모아 머리의 수를 다시 읽지 않고 맞춘다
 */
export function NowItem({
  card,
  item,
  readAt,
  control,
  onControlChange,
}: {
  card: AttentionCardKey;
  item: AttentionItem;
  readAt: string;
  control: ItemControl | null;
  onControlChange(control: ItemControl | null): void;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<ItemAction["kind"] | null>(null);
  const [editing, setEditing] = useState(false);

  const href = itemHref(item);
  const origin = originText(item);
  const actions = itemActions(item);
  const followUp = item.followUp;
  const problem = item.problem;
  const sources: ReactNode[] = [
    ...(item.agentName ? [item.agentName] : []),
    ...(origin ? [origin] : []),
    <time key="at" dateTime={item.at} title={formatFullTime(item.at)}>
      {formatRelative(item.at, new Date(readAt))}
    </time>,
  ];
  function opened() {
    recordAttentionEvent(item.itemKey, item.stateKey, "OPENED");
  }

  function acted() {
    recordAttentionEvent(item.itemKey, item.stateKey, "ACTED");
    router.refresh();
  }

  async function changeFollowUp(kind: ItemAction["kind"]) {
    const request = FOLLOW_UP_ACTIONS[kind];
    if (!request || !followUp) return;
    setPending(kind);
    setError(null);
    const result = await request(followUp.id);
    setPending(null);
    if (result.ok) acted();
    else setError(result.message);
  }

  async function reactToProblem(kind: ItemAction["kind"]) {
    const reaction = DECISION_REACTIONS[kind];
    if (!reaction || !problem) return;
    setPending(kind);
    setError(null);
    const result = await reactToDecision(problem.decisionId, reaction);
    setPending(null);
    if (result.ok) acted();
    else setError(result.message);
  }

  if (control) {
    return (
      <li
        data-testid="now-item"
        data-item-key={item.itemKey}
        data-attention={item.attention}
        className="flex flex-col gap-1"
      >
        <ControlledRow
          card={card}
          item={item}
          control={control}
          onRestored={() => onControlChange(null)}
          onError={setError}
        />
        {error ? <Notice variant="error">{error}</Notice> : null}
      </li>
    );
  }

  return (
    <li
      data-testid="now-item"
      data-item-key={item.itemKey}
      data-attention={item.attention}
      className="flex flex-col gap-1"
    >
      <NowItemHeader
        card={card}
        item={item}
        href={href}
        onOpen={opened}
        onControlled={onControlChange}
        onError={setError}
      />
      <p className="text-sm text-muted-foreground">{reasonText(item.why)}</p>
      {problem?.action ? (
        <p className="text-sm break-words">{problem.action}</p>
      ) : null}
      {problem?.level === "ASK_APPROVAL" ? (
        <p className="text-sm text-muted-foreground">
          직접 처리할 일이에요. 승인 요청이 아니에요.
        </p>
      ) : null}
      <p className="flex flex-wrap gap-x-1 text-xs text-muted-foreground">
        {sources.map((source, index) => (
          <Fragment key={index}>
            {index > 0 ? <span aria-hidden="true">·</span> : null}
            <span className="min-w-0 break-words">{source}</span>
          </Fragment>
        ))}
      </p>
      {actions.length > 0 ? (
        <ActionButtons
          actions={actions}
          href={href}
          pending={pending}
          onOpen={opened}
          onEdit={() => setEditing(true)}
          onChange={(kind) =>
            void (kind in DECISION_REACTIONS
              ? reactToProblem(kind)
              : changeFollowUp(kind))
          }
        />
      ) : null}
      {problem ? (
        <p className="text-xs text-muted-foreground">
          받아들임은 기록만 해요. 할 일이나 승인을 만들지 않아요.
        </p>
      ) : null}
      {error ? <Notice variant="error">{error}</Notice> : null}
      {followUp ? (
        <FollowUpDialog
          open={editing}
          onOpenChange={setEditing}
          followUp={{
            id: followUp.id,
            title: item.title,
            dueAt: followUp.dueAt,
            waiting: followUp.waiting,
          }}
          onSaved={() => {
            setEditing(false);
            acted();
          }}
        />
      ) : null}
    </li>
  );
}

/** 항목의 머리 줄이다. 제목 링크, 「지금」 과 실행 상태 배지, 오른쪽의 제어 메뉴를 둔다. */
function NowItemHeader({
  card,
  item,
  href,
  onOpen,
  onControlled,
  onError,
}: {
  card: AttentionCardKey;
  item: AttentionItem;
  href: string | null;
  onOpen(): void;
  onControlled(control: ItemControl): void;
  onError(message: string | null): void;
}) {
  const title = item.title || "새 대화";
  // 판정 응답에는 오류 코드가 없다. 상태만으로 문구와 색을 고른다.
  const execution = item.execution
    ? { status: item.execution.status, errorCode: null }
    : null;
  return (
    <div className="flex items-start gap-2">
      <div className="flex min-w-0 flex-1 flex-wrap items-start gap-2">
        {href ? (
          <Link
            href={href}
            prefetch={false}
            onClick={onOpen}
            className="min-w-0 flex-1 font-medium break-words hover:underline"
          >
            {title}
          </Link>
        ) : (
          <span className="min-w-0 flex-1 font-medium break-words">
            {title}
          </span>
        )}
        {item.attention === "NOW" ? (
          <Badge variant="warning">지금</Badge>
        ) : null}
        {execution ? (
          <Badge variant={executionStatusVariant(execution)}>
            {executionStatusLabel(execution, false)}
          </Badge>
        ) : null}
      </div>
      <NowItemControls
        card={card}
        item={item}
        onControlled={onControlled}
        onError={onError}
      />
    </div>
  );
}

/** 항목의 단추 줄이다. 좁은 폭에서도 제목과 이유 아래에 두고 넘치면 다음 줄로 내린다. */
function ActionButtons({
  actions,
  href,
  pending,
  onOpen,
  onEdit,
  onChange,
}: {
  actions: ItemAction[];
  href: string | null;
  pending: ItemAction["kind"] | null;
  onOpen(): void;
  onEdit(): void;
  onChange(kind: ItemAction["kind"]): void;
}) {
  return (
    <div className="mt-1 flex flex-wrap gap-2">
      {actions.map((action) =>
        action.kind === "link" && href ? (
          <Link
            key={action.kind}
            href={href}
            prefetch={false}
            onClick={onOpen}
            className={buttonVariants({ variant: "outline", size: "sm" })}
          >
            {action.label}
          </Link>
        ) : (
          <Button
            key={action.kind}
            variant="outline"
            size="sm"
            loading={pending === action.kind}
            disabled={pending !== null}
            onClick={() =>
              action.kind === "edit" ? onEdit() : onChange(action.kind)
            }
          >
            {action.label}
          </Button>
        ),
      )}
    </div>
  );
}
