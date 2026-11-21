"use client";

import { useEffect, useId, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
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
 * 크게 보기 창의 그림을 원래 크기로 맞춘다. mermaid 는 `width="100%"` 와 `max-width` 로 그려 좁은 본문에서 줄어든다.
 * 창에서는 viewBox 의 폭으로 그리고 넘치면 창 안에서 스크롤한다.
 */
function naturalSize(node: HTMLDivElement | null) {
  const svg = node?.querySelector("svg");
  if (!svg) return;
  const width = svg.viewBox.baseVal?.width;
  svg.style.maxWidth = "none";
  if (width) svg.style.width = `${width}px`;
}

/**
 * 누르면 화면을 덮는 창에서 그림을 원래 크기로 보인다(docs/features/app-shell/spec.md 의 DECISION26).
 * 같은 SVG 를 두 번 그리므로 창 쪽의 id 를 바꿔 스타일과 화살표 참조가 본문 쪽과 섞이지 않게 한다.
 */
function ExpandableDiagram({ id, svg }: { id: string; svg: string }) {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <button
          type="button"
          data-testid="mermaid-diagram"
          aria-label="그림 크게 보기"
          className="my-3 block w-full cursor-zoom-in overflow-x-auto rounded-md border border-border bg-white p-3 text-left"
          // mermaid 가 strict 로 정리한 SVG 다.
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      </DialogTrigger>
      <DialogContent
        data-testid="mermaid-dialog"
        closeLabel="그림 닫기"
        aria-describedby={undefined}
        className="flex h-[calc(100dvh-2rem)] w-[calc(100vw-2rem)] max-w-none flex-col gap-2 sm:max-w-none"
      >
        <DialogTitle className="pr-10">그림 크게 보기</DialogTitle>
        <div className="flex min-h-0 flex-1 overflow-auto rounded-md border border-border bg-white p-3">
          <div
            ref={naturalSize}
            data-testid="mermaid-dialog-diagram"
            className="m-auto"
            // mermaid 가 strict 로 정리한 SVG 다.
            dangerouslySetInnerHTML={{
              __html: svg.replaceAll(id, `${id}-large`),
            }}
          />
        </div>
      </DialogContent>
    </Dialog>
  );
}

/**
 * mermaid 블록이다. 개발 문서와 대화 답이 함께 쓴다(docs/features/answer-quality/spec.md 의 REQ19). 그리기 전과 실패하면 원문을 보인다.
 * 어두운 테마여도 흰 바탕에 그린다(docs/features/dev-overview/spec.md 의 DECISION16).
 * 손그림을 지원하지 않는 그림 종류(sequence 등)는 mermaid 기본 모양이다(같은 스펙의 DECISION8).
 * `expandable` 이면 눌러서 크게 본다. 개발 문서만 켠다.
 */
export function MermaidBlock({
  source,
  expandable = false,
}: {
  source: string;
  expandable?: boolean;
}) {
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
    if (expandable) return <ExpandableDiagram id={id} svg={state.svg} />;
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
