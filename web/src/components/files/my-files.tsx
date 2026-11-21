"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { describeFailure } from "@/components/error-message";
import { MyFilePreview } from "@/components/files/my-file-preview";
import { MyFileRow } from "@/components/files/my-file-row";
import {
  ConversationFilter,
  DeleteFileDialog,
  FileFilters,
  ListState,
  UsageLine,
} from "@/components/files/my-files-parts";
import {
  useEndSentinel,
  useLinkedItem,
  useMyFiles,
} from "@/components/files/use-my-files";
import {
  deleteVaultItem,
  VAULT_ERRORS,
  type VaultItem,
  type VaultKind,
} from "@/lib/artifact-vault-api";
import { displayName, groupByDate } from "@/lib/my-files";

/** 검색 칸의 입력이 이만큼 멎으면 다시 읽는다. */
const SEARCH_DELAY_MS = 300;

/**
 * 「내 파일」 이다(스펙 workspace REQ9~REQ18). 비서가 내준 파일을 넣은 시각의 최신순 한 목록으로 보인다.
 *
 * <p>위에서 아래로 용량 한 줄, 검색 칸과 종류 고르기, 대화 필터 표시, 날짜 구분 아래의 줄이다. 목록 끝이 보이면 다음 쪽을 읽는다.
 * 대화의 「내 파일에서 보기」 는 주소의 `item` 으로 파일 하나를 가리키고, 그 파일의 미리보기를 연 채 연다.
 */
export function MyFiles() {
  const params = useSearchParams();
  const router = useRouter();
  const [now] = useState(() => new Date());
  const [text, setText] = useState("");
  const [q, setQ] = useState("");
  const [kind, setKind] = useState<VaultKind | null>(null);
  const [conversation, setConversation] = useState<{
    id: string;
    title: string;
  } | null>(null);
  const [preview, setPreview] = useState<VaultItem | null>(null);
  // 미리보기를 연 줄의 단추다. Safari 는 눌러도 단추에 포커스를 주지 않아 activeElement 로 알 수 없다.
  const [opener, setOpener] = useState<HTMLElement | null>(null);
  const [deleting, setDeleting] = useState<VaultItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const list = useMyFiles({ kind, q, conversation: conversation?.id ?? null });
  const linked = Number(params.get("item"));

  useEffect(() => {
    const timer = setTimeout(() => setQ(text.trim()), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [text]);

  useLinkedItem(
    linked,
    (item) => {
      setOpener(null);
      setPreview(item);
    },
    setNotice,
  );
  const sentinel = useEndSentinel(list.nextCursor, list.loadMore);

  function closePreview() {
    setPreview(null);
    if (params.get("item") !== null)
      router.replace("/files", { scroll: false });
  }

  async function confirmDelete() {
    if (deleting === null) return;
    setBusy(true);
    try {
      const response = await deleteVaultItem(deleting.id);
      if (!response.ok && response.status !== 404) {
        setNotice(await describeFailure(response, VAULT_ERRORS));
        return;
      }
      if (preview?.id === deleting.id) closePreview();
      await list.removed(deleting.id);
      setDeleting(null);
    } catch {
      setNotice("지우지 못했어요. 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  }

  const filtered = q !== "" || kind !== null || conversation !== null;
  return (
    <div className="flex min-w-0 gap-4" data-testid="my-files">
      <div className="min-w-0 flex-1 space-y-3">
        {list.usage ? <UsageLine usage={list.usage} /> : null}
        <FileFilters
          text={text}
          kind={kind}
          onText={setText}
          onKind={setKind}
        />
        {conversation ? (
          <ConversationFilter
            id={conversation.id}
            title={conversation.title}
            onClear={() => setConversation(null)}
          />
        ) : null}
        {notice ? (
          <Notice variant="error" role="alert">
            {notice}
          </Notice>
        ) : null}
        <ListState
          failure={list.failure}
          items={list.items}
          filtered={filtered}
          onRetry={list.retry}
        />
        {groupByDate(list.items ?? [], now).map(({ group, items }) => (
          <section
            key={group.key}
            aria-label={group.label}
            data-testid="my-files-group"
          >
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">
              {group.label}
            </h2>
            <ul className="space-y-2">
              {items.map((item) => (
                <MyFileRow
                  key={item.id}
                  item={item}
                  now={now}
                  selected={preview?.id === item.id}
                  onOpen={(button) => {
                    setOpener(button);
                    setPreview(item);
                  }}
                  onConversation={(id, title) => setConversation({ id, title })}
                  onDelete={() => setDeleting(item)}
                />
              ))}
            </ul>
          </section>
        ))}
        {list.hasMore ? (
          <div ref={sentinel} className="flex flex-col items-center gap-2 py-2">
            {list.moreFailed ? (
              <p className="text-sm text-destructive">
                다음 파일을 불러오지 못했어요.
              </p>
            ) : null}
            <Button
              variant="outline"
              size="sm"
              loading={list.moreBusy}
              loadingText="불러오는 중…"
              onClick={() => void list.loadMore()}
            >
              더 보기
            </Button>
          </div>
        ) : null}
      </div>
      {preview ? (
        <MyFilePreview
          key={preview.id}
          item={preview}
          opener={opener}
          onClose={closePreview}
          onDelete={() => setDeleting(preview)}
        />
      ) : null}
      {deleting ? (
        <DeleteFileDialog
          name={displayName(deleting.path, deleting.title)}
          busy={busy}
          onCancel={() => setDeleting(null)}
          onConfirm={() => void confirmDelete()}
        />
      ) : null}
    </div>
  );
}
