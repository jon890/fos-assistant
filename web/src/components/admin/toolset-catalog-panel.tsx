"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { toolsetText } from "@/lib/toolset-label";
import {
  fetchToolsetCatalog,
  saveHiddenToolsets,
  type CatalogToolset,
} from "@/lib/toolset-catalog";

/** 그룹 전체의 도구 선택 목록을 정한다. 활성 도구를 끄는 일은 에이전트 화면에서 한다. */
export function ToolsetCatalogPanel() {
  const [catalog, setCatalog] = useState<CatalogToolset[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(
    null,
  );

  async function load() {
    try {
      setCatalog(await fetchToolsetCatalog());
      setMessage(null);
    } catch {
      setMessage({
        ok: false,
        text: "도구 목록을 불러오지 못했어요. 다시 불러와 주세요.",
      });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;
    void fetchToolsetCatalog()
      .then((entries) => {
        if (active) setCatalog(entries);
      })
      .catch(() => {
        if (active)
          setMessage({
            ok: false,
            text: "도구 목록을 불러오지 못했어요. 다시 불러와 주세요.",
          });
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function toggle(tool: CatalogToolset) {
    if (catalog === null || saving || loading) return;
    setSaving(true);
    setMessage(null);
    const next = catalog.map((entry) =>
      entry.name === tool.name ? { ...entry, hidden: !entry.hidden } : entry,
    );
    try {
      await saveHiddenToolsets(
        next.filter((entry) => entry.hidden).map((entry) => entry.name),
      );
      setCatalog(next);
      setMessage({ ok: true, text: "화면에 보일 도구를 저장했어요." });
    } catch {
      setMessage({
        ok: false,
        text: "도구 숨김을 저장하지 못했어요. 다시 시도해 주세요.",
      });
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="mx-auto w-full max-w-2xl" aria-label="화면에 보일 도구">
      <h1 className="text-xl font-semibold">화면에 보일 도구</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        그룹 전체의 에이전트 도구 화면에 보여요. 숨겨도 이미 켜진 도구는 계속 쓸
        수 있어요. 도구를 끄려면 아래 에이전트를 열어 주세요.
      </p>
      {message ? (
        <Notice
          className="mt-4"
          variant={message.ok ? "success" : "error"}
          role={message.ok ? "status" : "alert"}
        >
          {message.text}
        </Notice>
      ) : null}
      <Button
        className="mt-4"
        variant="outline"
        size="sm"
        disabled={saving || loading}
        onClick={() => {
          setLoading(true);
          setMessage(null);
          void load();
        }}
      >
        다시 불러오기
      </Button>
      {catalog === null ? (
        message === null ? (
          <p className="mt-4 text-sm">도구 목록을 불러오고 있어요.</p>
        ) : null
      ) : (
        <ul className="mt-4 divide-y divide-border rounded-md border border-border">
          {catalog.map((tool) => {
            const text = toolsetText(tool.name, tool);
            return (
              <li
                key={tool.name}
                className="flex items-start justify-between gap-3 p-3"
              >
                <div className="min-w-0">
                  <p className="text-sm font-medium">{text.label}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {text.description}
                  </p>
                  <p className="mt-1 text-xs">
                    {tool.hidden ? "숨김" : "보임"} · 켜진 에이전트{" "}
                    {tool.enabledAgents.length}개
                  </p>
                  {tool.enabledAgents.length > 0 ? (
                    <ul className="mt-2 grid gap-1 text-sm">
                      {tool.enabledAgents.map((agent) => (
                        <li key={agent.code}>
                          <Link
                            className="underline"
                            prefetch={false}
                            href={`/admin/agents/${agent.code}`}
                          >
                            {agent.name}
                          </Link>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </div>
                <Switch
                  aria-label={`${text.label} 보이기`}
                  checked={!tool.hidden}
                  disabled={saving || loading}
                  onCheckedChange={() => void toggle(tool)}
                />
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
