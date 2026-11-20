"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import { FRAME_SANDBOX } from "@/components/chat/artifact/artifact-panel";
import { CsvTable } from "@/components/workspace/csv-table";
import {
  fetchVaultContent,
  vaultArchiveUrl,
  vaultContentUrl,
  vaultFileUrl,
  type VaultItem,
} from "@/lib/artifact-vault-api";
import { decodeText, displayName, filePreview } from "@/lib/my-files";
import { parseDelimited } from "@/lib/workspace-file";

/** 표로 그리는 최대 줄 수다. 머리 줄도 센다. 실행 공간 미리보기와 같다. */
const TABLE_MAX_ROWS = 1000;

/** 묶음이면 zip, 아니면 파일 하나를 내려받는 주소다. */
export function downloadUrl(item: VaultItem): string {
  return item.fileCount > 1
    ? vaultArchiveUrl(item.id)
    : vaultContentUrl(item.id, true);
}

/** 글과 표 미리보기의 본문이다. 받은 글은 글자로만 그린다. 파일이 바뀌면 부르는 쪽이 `key` 로 새로 만든다. */
function TextBody({ item, kind }: { item: VaultItem; kind: "text" | "table" }) {
  // 받기 전은 null, 받지 못했으면 false 다.
  const [text, setText] = useState<string | false | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchVaultContent(item.id)
      .then<string | false>(async (response) =>
        response.ok ? decodeText(await response.arrayBuffer()) : false,
      )
      .catch((): false => false)
      .then((next) => {
        if (!cancelled) setText(next);
      });
    return () => {
      cancelled = true;
    };
  }, [item.id]);

  if (text === null)
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  if (text === false)
    return (
      <Notice variant="error">
        미리보기를 불러오지 못했어요. 내려받아 열어 주세요.
      </Notice>
    );
  if (kind === "text")
    return (
      <pre
        data-testid="my-file-text"
        className="font-mono text-xs break-words whitespace-pre-wrap"
      >
        {text}
      </pre>
    );
  const delimiter = item.path.toLowerCase().endsWith(".tsv") ? "\t" : ",";
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

function PreviewBody({ item, name }: { item: VaultItem; name: string }) {
  const kind = filePreview(item.path, item.byteSize);
  if (kind === "html")
    return (
      // 에이전트가 만든 HTML 은 흰 바탕을 전제로 하므로 토큰이 아니라 bg-white 를 그대로 둔다.
      // 보관본 안의 파일 주소로 열어 HTML 이 상대 주소로 부르는 사진과 스타일도 같은 보관본에서 읽는다.
      <iframe
        key={item.id}
        title={name}
        sandbox={FRAME_SANDBOX}
        src={vaultFileUrl(item.id, item.path)}
        data-testid="my-file-frame"
        className="min-h-0 w-full flex-1 border-0 bg-white"
      />
    );
  return (
    <div className="min-h-0 flex-1 overflow-auto p-4">
      {kind === "image" ? (
        // eslint-disable-next-line @next/next/no-img-element -- 보관본 주소는 next/image 최적화를 거치지 않는다
        <img
          src={vaultContentUrl(item.id)}
          alt={name}
          data-testid="my-file-image"
          className="max-w-full"
        />
      ) : kind === "text" || kind === "table" ? (
        <TextBody key={item.id} item={item} kind={kind} />
      ) : (
        <Notice variant="neutral">
          미리보기가 없어요. 내려받아 열어 주세요.
        </Notice>
      )}
    </div>
  );
}

/**
 * 「내 파일」 의 파일 하나를 미리 본다(스펙 workspace REQ14). 넓은 화면은 목록 옆 패널, 좁은 화면은 전체 폭 시트다.
 *
 * <p>그림은 그대로, HTML 은 스크립트 없이, CSV·TSV 는 표로, MD·TXT·CSS 는 원문 그대로 보인다. 나머지는 내려받기만 된다.
 */
export function MyFilePreview({
  item,
  wide,
  onClose,
  onDelete,
}: {
  item: VaultItem;
  /** 넓은 화면이면 목록 옆 패널, 좁으면 전체 폭 시트다. 폭을 아직 모르면(null) 옆 패널로 그린다. */
  wide: boolean | null;
  onClose(): void;
  onDelete(): void;
}) {
  const name = displayName(item.path, item.title);
  const titleClass = "min-w-0 flex-1 truncate text-sm font-semibold";
  const header = (title: React.ReactNode) => (
    <header className="flex min-w-0 flex-wrap items-center gap-2 border-b border-border px-4 py-3">
      {title}
      <div className="flex shrink-0 items-center gap-1">
        <Button variant="outline" size="sm" asChild>
          <a href={downloadUrl(item)}>
            {item.fileCount > 1 ? "묶음 내려받기" : "내려받기"}
          </a>
        </Button>
        <Button variant="outline" size="sm" onClick={onDelete}>
          지우기
        </Button>
        <Button variant="ghost" size="sm" onClick={onClose}>
          닫기
        </Button>
      </div>
    </header>
  );
  const body = <PreviewBody item={item} name={name} />;

  if (wide !== false)
    return (
      <aside
        data-testid="my-file-preview"
        aria-label="미리보기"
        className="sticky top-4 flex h-[75vh] w-1/2 min-w-0 shrink-0 flex-col rounded-md border border-border bg-card"
      >
        {header(
          <h2 className={titleClass} title={item.path}>
            {name}
          </h2>,
        )}
        {body}
      </aside>
    );

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
        data-testid="my-file-preview"
        showCloseButton={false}
        aria-describedby={undefined}
        className="min-w-0 gap-0 border-border bg-card data-[side=right]:w-full data-[side=right]:sm:max-w-none"
      >
        {header(
          <SheetTitle className={titleClass} title={item.path}>
            {name}
          </SheetTitle>,
        )}
        {body}
      </SheetContent>
    </Sheet>
  );
}
