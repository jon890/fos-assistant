"use client";

import { useEffect, useId, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  changeLabel,
  countedForLabel,
  draftChanged,
  draftOf,
  grantsOf,
  missingNotice,
  type AgentMemoryCollection,
  type AgentMemoryDraft,
  type AgentMemorySetting,
} from "@/lib/agent-memory";
import {
  getAgentMemorySetting,
  saveAgentMemorySetting,
} from "@/lib/agent-memory-api";

type Props = { code: string };

type State =
  | { status: "loading" }
  | { status: "failed"; message: string }
  | { status: "loaded"; setting: AgentMemorySetting; draft: AgentMemoryDraft };

function formatChangedAt(value: string) {
  return new Intl.DateTimeFormat("ko-KR", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: "Asia/Seoul",
  }).format(new Date(value));
}

type Pick = { granted: boolean; allowSensitive: boolean };

/** 영역 하나의 줄이다. 안내는 저장된 값이 아니라 지금 고른 값 `row` 로 그린다. */
function CollectionRow({
  collection,
  row,
  disabled,
  onPick,
}: {
  collection: AgentMemoryCollection;
  row: Pick;
  disabled: boolean;
  onPick(next: Pick): void;
}) {
  const missing = missingNotice(collection, row);
  return (
    <li className="grid gap-1">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="font-medium">{collection.displayName}</span>
        {collection.listed ? null : (
          <Badge variant="outline">목록에 없는 영역</Badge>
        )}
        <span className="text-sm text-muted-foreground">
          항목 {collection.entryCount}개
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            className="size-4 accent-primary"
            aria-label={`${collection.displayName} 받음`}
            checked={row.granted}
            disabled={disabled}
            onChange={(event) =>
              onPick({
                granted: event.target.checked,
                // 받음을 끄면 민감 허용도 함께 꺼진다.
                allowSensitive: event.target.checked && row.allowSensitive,
              })
            }
          />
          받음
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            className="size-4 accent-primary"
            aria-label={`${collection.displayName} 민감 항목까지`}
            checked={row.granted && row.allowSensitive}
            disabled={disabled || !row.granted}
            onChange={(event) =>
              onPick({
                granted: row.granted,
                allowSensitive: event.target.checked,
              })
            }
          />
          민감 항목까지
        </label>
      </div>
      {missing ? <p className="text-sm text-warning">{missing}</p> : null}
    </li>
  );
}

function RecentChanges({ setting }: { setting: AgentMemorySetting }) {
  const headingId = useId();
  return (
    <>
      <h3 id={headingId} className="mt-5 text-sm font-medium">
        최근 변경
      </h3>
      {setting.changes.length === 0 ? (
        <p className="mt-2 text-sm text-muted-foreground">
          아직 바꾼 기록이 없어요.
        </p>
      ) : (
        <ul aria-labelledby={headingId} className="mt-2 grid gap-1 text-sm">
          {setting.changes.map((change, index) => (
            <li key={`${change.changedAt}-${index}`}>
              <time
                dateTime={change.changedAt}
                className="text-muted-foreground"
              >
                {formatChangedAt(change.changedAt)}
              </time>{" "}
              {change.changedByName ?? "알 수 없는 사용자"}:{" "}
              {changeLabel(
                change,
                setting.collections.find(
                  (collection) => collection.key === change.collection,
                )?.displayName ?? change.collection,
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

/**
 * 관리자가 이 에이전트가 대화에 쓸 기억의 영역과 영역별 민감 항목 허용을 고른다.
 *
 * <p>저장은 고른 전체를 보낸다. 빠진 영역의 안내는 저장된 값이 아니라 지금 고른 값으로 그려서,
 * 고르는 즉시 저장하면 어떻게 되는지 보인다. 읽지 못해도 에이전트의 다른 절은 그대로 쓴다.
 */
export function AgentMemorySection({ code }: Props) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(
    null,
  );

  useEffect(() => {
    let active = true;
    void getAgentMemorySetting(code).then((result) => {
      if (!active) return;
      setState(
        result.ok
          ? {
              status: "loaded",
              setting: result.data,
              draft: draftOf(result.data),
            }
          : { status: "failed", message: result.message },
      );
    });
    return () => {
      active = false;
    };
  }, [code]);

  function pick(
    key: string,
    next: { granted: boolean; allowSensitive: boolean },
  ) {
    setMessage(null);
    setState((current) =>
      current.status === "loaded"
        ? { ...current, draft: { ...current.draft, [key]: next } }
        : current,
    );
  }

  async function save(setting: AgentMemorySetting, draft: AgentMemoryDraft) {
    setSaving(true);
    setMessage(null);
    const result = await saveAgentMemorySetting(code, grantsOf(draft));
    setSaving(false);
    if (!result.ok) {
      setMessage({ ok: false, text: result.message });
      return;
    }
    setState({
      status: "loaded",
      setting: result.data,
      draft: draftOf(result.data),
    });
    setMessage({ ok: true, text: "저장했어요." });
  }

  return (
    <section
      aria-label="기억 영역"
      data-testid="agent-memory-section"
      className="mx-auto mt-8 w-full max-w-2xl rounded-md border border-border p-4"
    >
      <h2 className="font-semibold">기억 영역</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        이 에이전트가 대화에 쓸 수 있는 기억의 영역이에요. 민감 항목은 영역마다
        따로 허용해요.
      </p>
      {state.status === "loading" ? (
        <p className="mt-3 text-sm text-muted-foreground">
          기억 영역을 불러오고 있어요.
        </p>
      ) : state.status === "failed" ? (
        <Notice variant="error" role="alert" className="mt-3">
          기억 영역을 불러오지 못했어요. {state.message}
        </Notice>
      ) : (
        <>
          <ul className="mt-3 grid gap-3">
            {state.setting.collections.map((collection) => (
              <CollectionRow
                key={collection.key}
                collection={collection}
                row={
                  state.draft[collection.key] ?? {
                    granted: collection.granted,
                    allowSensitive: collection.allowSensitive,
                  }
                }
                disabled={saving}
                onPick={(next) => pick(collection.key, next)}
              />
            ))}
          </ul>
          {Object.values(state.draft).every((row) => !row.granted) ? (
            <Notice variant="warning" className="mt-3">
              받는 영역이 없으면 이 에이전트는 기억을 쓰지 않아요.
            </Notice>
          ) : null}
          <p className="mt-3 text-sm text-muted-foreground">
            {countedForLabel(state.setting)}
          </p>
          {message ? (
            <Notice
              variant={message.ok ? "success" : "error"}
              role={message.ok ? "status" : "alert"}
              className="mt-3"
            >
              {message.text}
            </Notice>
          ) : null}
          <div className="mt-3">
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={saving || !draftChanged(state.setting, state.draft)}
              loading={saving}
              loadingText="저장하는 중"
              onClick={() => void save(state.setting, state.draft)}
            >
              기억 영역 저장
            </Button>
          </div>
          <RecentChanges setting={state.setting} />
        </>
      )}
    </section>
  );
}
