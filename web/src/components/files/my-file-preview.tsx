"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from "@/components/ui/sheet";
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
 * 「내 파일」 의 파일 하나를 미리 본다(스펙 workspace REQ14). 화면 폭과 상관없이 목록 위를 덮는 시트다.
 * 휴대폰은 전체 폭이고, 넓은 화면은 바깥을 눌러 닫을 수 있게 왼쪽에 목록이 조금 남는다(app-shell spec DECISION26).
 * 포커스가 HTML 미리보기 iframe 안에 있으면 Esc 가 이 창까지 오지 않아 「닫기」 단추로 닫는다.
 *
 * <p>그림은 그대로, HTML 은 스크립트 없이, CSV·TSV 는 표로, MD·TXT·CSS 는 원문 그대로 보인다. 나머지는 내려받기만 된다.
 */
export function MyFilePreview({
  item,
  opener,
  onClose,
  onDelete,
}: {
  item: VaultItem;
  /** 시트를 연 단추다. 시트에 trigger 가 없어 Radix 가 포커스를 돌려줄 곳을 모르므로 닫은 뒤 여기로 돌려준다. 대화의 링크로 열었으면 null 이다. */
  opener: HTMLElement | null;
  onClose(): void;
  onDelete(): void;
}) {
  const name = displayName(item.path, item.title);
  const [open, setOpen] = useState(true);
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      {/* 닫기 단추는 머리의 「닫기」 하나만 둔다. */}
      <SheetContent
        side="right"
        data-testid="my-file-preview"
        showCloseButton={false}
        aria-describedby={undefined}
        // 닫는 움직임이 끝난 뒤 연 줄로 포커스를 돌려주고 부모에 알린다.
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          opener?.focus();
          onClose();
        }}
        className="min-w-0 gap-0 border-border bg-card data-[side=right]:w-full data-[side=right]:sm:max-w-none data-[side=right]:lg:w-[min(72rem,calc(100%-4rem))]"
      >
        <header className="flex min-w-0 flex-wrap items-center gap-2 border-b border-border px-4 py-3">
          <SheetTitle
            className="min-w-0 flex-1 truncate text-sm font-semibold"
            title={item.path}
          >
            {name}
          </SheetTitle>
          <div className="flex shrink-0 items-center gap-1">
            <Button variant="outline" size="sm" asChild>
              <a href={downloadUrl(item)}>
                {item.fileCount > 1 ? "묶음 내려받기" : "내려받기"}
              </a>
            </Button>
            <Button variant="outline" size="sm" onClick={onDelete}>
              지우기
            </Button>
            <SheetClose asChild>
              <Button variant="ghost" size="sm">
                닫기
              </Button>
            </SheetClose>
          </div>
        </header>
        <PreviewBody item={item} name={name} />
      </SheetContent>
    </Sheet>
  );
}
