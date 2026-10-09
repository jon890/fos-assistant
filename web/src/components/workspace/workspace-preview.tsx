"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { FRAME_SANDBOX } from "@/components/chat/artifact/artifact-panel";
import { CsvTable } from "@/components/workspace/csv-table";
import { fetchWorkspaceFile } from "@/lib/workspace-api";
import {
  fileUrl,
  parseDelimited,
  previewKind,
  type PreviewKind,
} from "@/lib/workspace-file";

/** 표로 그리는 최대 줄 수다. 머리 줄도 센다. */
const TABLE_MAX_ROWS = 1000;

type Props = {
  /** 사용자 디렉터리 안의 상대 경로다. */
  path: string;
  name: string;
  size: number | null;
  /** 넓은 화면이면 목록 옆 패널, 좁으면 전체 폭 시트다. 폭을 아직 모르면(null) 옆 패널로 그린다. */
  wide: boolean | null;
  onClose(): void;
};

/** 글과 표 미리보기의 본문이다. 받은 글은 글자로만 그린다. 파일이 바뀌면 부르는 쪽이 `key` 로 새로 만든다. */
function TextBody({ path, kind }: { path: string; kind: "text" | "table" }) {
  // 받기 전은 null, 받지 못했으면 false 다.
  const [text, setText] = useState<string | false | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchWorkspaceFile(path)
      .then<string | false>((response) =>
        response.ok ? response.text() : false,
      )
      .catch((): false => false)
      .then((next) => {
        if (!cancelled) setText(next);
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  if (text === null) {
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  }
  // 글자로 읽을 수 없는 내용을 깨진 글자로 그리지 않는다. NUL 은 글 파일에 들지 않는다.
  if (text === false || text.includes("\u0000")) {
    return (
      <Notice variant={text === false ? "error" : "neutral"}>
        {text === false
          ? "미리보기를 불러오지 못했어요."
          : "글 파일이 아니에요."}{" "}
        내려받아 열어 주세요.
      </Notice>
    );
  }
  if (kind === "text") {
    return (
      <pre
        data-testid="workspace-text"
        className="font-mono text-xs break-words whitespace-pre-wrap"
      >
        {text}
      </pre>
    );
  }
  const delimiter = path.toLowerCase().endsWith(".tsv") ? "\t" : ",";
  const { rows, truncated } = parseDelimited(text, delimiter, TABLE_MAX_ROWS);
  return (
    <div className="space-y-2">
      <CsvTable rows={rows} />
      {truncated ? (
        <p className="text-sm text-muted-foreground">1,000줄까지만 보여요</p>
      ) : null}
    </div>
  );
}

function PreviewBody({
  path,
  name,
  kind,
}: {
  path: string;
  name: string;
  kind: PreviewKind;
}) {
  if (kind === "html") {
    return (
      // 에이전트가 만든 HTML 은 제 배경을 적지 않는 일이 많다. 어두운 테마에서도 글이 읽히도록 흰 바탕에 띄운다.
      // 에이전트가 만든 HTML 은 흰 바탕을 전제로 하므로 토큰이 아니라 bg-white 를 그대로 둔다.
      <iframe
        key={path}
        title={name}
        sandbox={FRAME_SANDBOX}
        src={fileUrl(path)}
        data-testid="workspace-frame"
        className="min-h-0 w-full flex-1 border-0 bg-white"
      />
    );
  }
  return (
    <div className="min-h-0 flex-1 overflow-auto p-4">
      {kind === "text" || kind === "table" ? (
        <TextBody key={path} path={path} kind={kind} />
      ) : kind === "image" ? (
        // 사진 크기를 모르고 Next 의 사진 최적화를 거치지 않는다. 받은 본문을 그대로 띄운다.
        <img src={fileUrl(path)} alt={name} className="max-w-full" />
      ) : (
        <Notice variant="neutral">
          미리보기가 없어요. 내려받아 열어 주세요.
        </Notice>
      )}
    </div>
  );
}

/**
 * 파일 하나의 미리보기다. 종류는 확장자와 목록의 크기로 정하고, 상한을 넘으면 본문을 요청하지 않는다.
 *
 * <p>자리 규칙은 결과물 패널과 같다. 넓은 화면은 목록 옆 패널, 좁은 화면은 전체 폭 시트다.
 */
export function WorkspacePreview({ path, name, size, wide, onClose }: Props) {
  const kind = previewKind(name, size);
  const titleClass = "min-w-0 flex-1 truncate text-sm font-semibold";
  const header = (title: React.ReactNode) => (
    <header className="flex min-w-0 items-center gap-2 border-b border-border px-4 py-3">
      {title}
      <a
        href={fileUrl(path, true)}
        className="shrink-0 text-xs text-foreground underline underline-offset-4"
      >
        내려받기
      </a>
      <Button variant="ghost" size="sm" onClick={onClose}>
        닫기
      </Button>
    </header>
  );
  const body = <PreviewBody path={path} name={name} kind={kind} />;

  if (wide !== false) {
    return (
      <aside
        data-testid="workspace-preview"
        aria-label="미리보기"
        className="flex h-[75vh] w-1/2 min-w-0 shrink-0 flex-col rounded-md border border-border bg-card"
      >
        {header(
          <h2 className={titleClass} title={path}>
            {name}
          </h2>,
        )}
        {body}
      </aside>
    );
  }

  return (
    <Sheet
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      {/* 닫기 단추는 머리의 「닫기」 하나만 둔다. */}
      <SheetContent
        side="right"
        data-testid="workspace-preview"
        showCloseButton={false}
        aria-describedby={undefined}
        className="min-w-0 gap-0 border-border bg-card data-[side=right]:w-full data-[side=right]:sm:max-w-none"
      >
        {header(
          <SheetTitle className={titleClass} title={path}>
            {name}
          </SheetTitle>,
        )}
        {body}
      </SheetContent>
    </Sheet>
  );
}
