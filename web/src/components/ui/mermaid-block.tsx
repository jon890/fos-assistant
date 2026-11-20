"use client";

import { useEffect, useId, useState } from "react";
import { mermaidRenderId } from "@/lib/mermaid";

type MermaidApi = (typeof import("mermaid"))["default"];

let mermaidPromise: Promise<MermaidApi> | null = null;

/**
 * mermaid 는 무거워 블록이 있을 때만 늦게 불러 한 번 설정한다.
 * 손그림이 기본이고 블록 머리의 설정이 덮는다. `securityLevel` 은 mermaid 가 문서 설정으로 바꾸지 못하게 막는다.
 */
function loadMermaid(): Promise<MermaidApi> {
  mermaidPromise ??= import("mermaid").then(({ default: mermaid }) => {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: "default",
      look: "handDrawn",
    });
    return mermaid;
  });
  return mermaidPromise;
}

type State =
  | { status: "pending" }
  | { status: "done"; svg: string }
  | { status: "failed" };

/**
 * mermaid 블록이다. 개발 문서와 대화 답이 함께 쓴다(docs/features/answer-quality/spec.md 의 REQ19). 그리기 전과 실패하면 원문을 보인다.
 * 어두운 테마여도 흰 바탕에 그린다(docs/features/dev-overview/spec.md 의 DECISION16).
 * 손그림을 지원하지 않는 그림 종류(sequence 등)는 mermaid 기본 모양이다(같은 스펙의 DECISION8).
 */
export function MermaidBlock({ source }: { source: string }) {
  const id = mermaidRenderId(useId());
  const [state, setState] = useState<State>({ status: "pending" });

  useEffect(() => {
    let active = true;
    void loadMermaid()
      .then((mermaid) => mermaid.render(id, source))
      .then(({ svg }) => {
        if (active) setState({ status: "done", svg });
      })
      .catch(() => {
        // 실패하면 mermaid 가 body 에 남긴 임시 요소를 지운다.
        document.getElementById(id)?.remove();
        document.getElementById(`d${id}`)?.remove();
        if (active) setState({ status: "failed" });
      });
    return () => {
      active = false;
    };
  }, [id, source]);

  if (state.status === "done") {
    return (
      <div
        data-testid="mermaid-diagram"
        className="my-3 overflow-x-auto rounded-md border border-border bg-white p-3"
        // mermaid 가 strict 로 정리한 SVG 다.
        dangerouslySetInnerHTML={{ __html: state.svg }}
      />
    );
  }
  return (
    <div className="my-3">
      {state.status === "failed" ? (
        <p role="status" className="mb-1 text-xs text-destructive">
          그림을 그리지 못했어요
        </p>
      ) : null}
      <pre className="overflow-x-auto rounded-md border border-border bg-muted p-3 font-mono text-xs leading-5">
        <code>{source}</code>
      </pre>
    </div>
  );
}
