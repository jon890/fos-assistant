"use client";

import { useEffect, useState } from "react";
import { ArrowDown, ArrowUp, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TooltipButton } from "@/components/ui/tooltip-button";
import type { AgentModel } from "@/lib/agent";

type Row = { provider: string; model: string };

type Props = {
  agentCode: string;
  models: AgentModel[];
  busy: boolean;
  /** 이 목록의 저장 요청이 돌고 있다. 「모델 목록 저장」 에만 회전 표시를 둔다. */
  saving: boolean;
  onSave(rows: Row[]): void;
};

function toRows(models: AgentModel[]): Row[] {
  return models.map((model) => ({ provider: model.provider, model: model.model }));
}

function moved(rows: Row[], from: number, to: number): Row[] {
  const next = [...rows];
  const [row] = next.splice(from, 1);
  if (row) next.splice(to, 0, row);
  return next;
}

/**
 * 에이전트가 쓸 모델을 순위 순서로 고친다.
 *
 * <p>`provider` 와 모델은 글자로 받는다. 고르는 목록을 만들지 않는다. Hermes 가 어떤 provider 와
 * 모델을 아는지 우리가 알 방법이 없고, 목록을 만들면 새 모델이 나올 때마다 화면을 고쳐야 한다.
 *
 * <p>마지막 한 줄은 지우지 못한다. 서버도 빈 목록을 거절하지만, 화면에서 먼저 막아야 저장을 누른
 * 뒤에 알게 되지 않는다.
 */
export function AgentModelList({ agentCode, models, busy, saving, onSave }: Props) {
  const [rows, setRows] = useState<Row[]>(toRows(models));
  const saved = JSON.stringify(toRows(models));

  useEffect(() => {
    setRows(toRows(models));
  }, [saved]);

  const dirty = JSON.stringify(rows) !== saved;
  const incomplete = rows.some((row) => row.provider.trim() === "" || row.model.trim() === "");

  function change(index: number, patch: Partial<Row>) {
    setRows((previous) =>
      previous.map((row, position) => (position === index ? { ...row, ...patch } : row)),
    );
  }

  return (
    <section aria-label={`${agentCode} 모델 목록`} data-testid="agent-model-list" className="mt-4">
      <h3 className="text-xs text-muted-foreground">모델 목록</h3>
      <ol className="mt-2 grid gap-2">
        {rows.map((row, index) => (
          // 한 번만 쓰는 열 배치다. 순위, provider, 모델, 조작 단추의 폭을 넓은 폭에서만 나란히 둔다.
          <li key={index} className="grid gap-2 sm:grid-cols-[2.5rem_minmax(0,1fr)_minmax(0,1.4fr)_auto] sm:items-center">
            <span className="text-xs text-muted-foreground">{index + 1}순위</span>
            <Input
              aria-label={`${index + 1}순위 모델 제공사`}
              value={row.provider}
              disabled={busy}
              onChange={(event) => change(index, { provider: event.target.value })}
            />
            <Input
              aria-label={`${index + 1}순위 모델`}
              value={row.model}
              disabled={busy}
              onChange={(event) => change(index, { model: event.target.value })}
            />
            {/* 셋 모두 저장 전의 줄만 바꾸고 요청을 보내지 않아 되돌릴 수 있다. 그래서 회전 표시도 destructive 도 두지 않는다. */}
            <span className="flex flex-wrap gap-1">
              <TooltipButton
                label="위로"
                size="icon"
                disabled={busy || index === 0}
                onClick={() => setRows(moved(rows, index, index - 1))}
              >
                <ArrowUp aria-hidden="true" />
              </TooltipButton>
              <TooltipButton
                label="아래로"
                size="icon"
                disabled={busy || index === rows.length - 1}
                onClick={() => setRows(moved(rows, index, index + 1))}
              >
                <ArrowDown aria-hidden="true" />
              </TooltipButton>
              <TooltipButton
                label="지우기"
                size="icon"
                disabled={busy || rows.length === 1}
                onClick={() => setRows(rows.filter((_, position) => position !== index))}
              >
                <X aria-hidden="true" />
              </TooltipButton>
            </span>
          </li>
        ))}
      </ol>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={() => setRows([...rows, { provider: "", model: "" }])}
        >
          모델 추가
        </Button>
        <Button
          size="sm"
          disabled={busy || !dirty || incomplete}
          loading={saving}
          loadingText="저장 중"
          onClick={() => onSave(rows)}
        >
          모델 목록 저장
        </Button>
      </div>
    </section>
  );
}
