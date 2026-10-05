"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { reasonText, type AttentionItem } from "@/lib/attention";
import { openProactiveReport } from "@/lib/proactive-report-api";

type ReportRow = {
  label: string;
  values: string[];
};

/** 다섯 칸 보고 하나를 요약해 그리고, 사용자가 열면 읽은 시각을 먼저 남긴다. */
export function ProactiveReportItem({ item }: { item: AttentionItem }) {
  const router = useRouter();
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const report = item.report;

  if (report === null) return null;

  const rows: ReportRow[] = [
    { label: "무엇이 바뀌었나", values: report.changed },
    { label: "무엇을 했나", values: report.done },
    { label: "근거", values: report.evidence },
    {
      label: "남은 승인",
      values:
        report.needsApproval.length === 0
          ? []
          : [
              `승인 ${report.needsApproval.length}건이 남아 있어요. 점검 대화에서 확인해 주세요.`,
            ],
    },
    { label: "다음에 볼 것", values: report.next },
  ];
  const checkId = report.checkId;

  async function open() {
    if (opening) return;
    setOpening(true);
    setError(null);
    try {
      const response = await openProactiveReport(checkId);
      if (!response.ok) {
        setError("보고를 열지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        return;
      }
      if (item.conversationId) router.push(`/chat/${item.conversationId}`);
      else router.refresh();
    } catch {
      setError("보고를 열지 못했어요. 잠시 뒤 다시 시도해 주세요.");
    } finally {
      setOpening(false);
    }
  }

  return (
    <li data-testid="proactive-report-item" className="flex flex-col gap-3">
      <h3 className="font-medium">{item.title}</h3>
      <p className="text-sm text-muted-foreground">{reasonText(item.why)}</p>
      <dl className="flex flex-col gap-3 text-sm">
        {rows.map((row) => (
          <div key={row.label}>
            <dt className="font-medium">{row.label}</dt>
            {row.values.length > 0 ? (
              <dd className="mt-1 text-muted-foreground">
                <ul className="flex flex-col gap-1">
                  {row.values.map((value) => (
                    <li key={value} className="whitespace-pre-wrap">
                      {row.label === "근거" ? (
                        <a
                          href={value}
                          target="_blank"
                          rel="noreferrer"
                          className="underline-offset-4 hover:text-foreground hover:underline"
                        >
                          {sourceLabel(value)}
                        </a>
                      ) : (
                        value
                      )}
                    </li>
                  ))}
                </ul>
              </dd>
            ) : (
              <dd className="mt-1 text-muted-foreground">없어요.</dd>
            )}
          </div>
        ))}
      </dl>
      <div>
        <Button
          size="sm"
          variant="outline"
          loading={opening}
          onClick={() => void open()}
        >
          보고 열기
        </Button>
      </div>
      {error ? <Notice variant="error">{error}</Notice> : null}
    </li>
  );
}

function sourceLabel(value: string): string {
  try {
    return new URL(value).hostname;
  } catch {
    return value;
  }
}
