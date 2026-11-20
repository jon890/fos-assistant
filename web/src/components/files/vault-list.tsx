"use client";

import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { describeFailure } from "@/components/error-message";
import { FRAME_SANDBOX } from "@/components/chat/artifact/artifact-panel";
import {
  deleteVaultItem,
  fetchVaultItems,
  isVaultImage,
  VAULT_ERRORS,
  vaultArchiveUrl,
  vaultContentUrl,
  vaultFileUrl,
  type VaultItem,
  type VaultList,
} from "@/lib/artifact-vault-api";
import { artifactName, imageTitle } from "@/lib/artifact-name";
import { formatFullTime } from "@/lib/format";
import { formatSize } from "@/lib/workspace-file";

/**
 * 「보관한 결과물」 목록이다. 대화에서 골라 보관한 HTML·CSS 와 사진, 비서가 만든 그림을 읽고 지운다. 그림 줄은 제목 앞에 작은 그림을 보인다.
 *
 * <p>HTML 은 스크립트가 돌지 않는 iframe 으로 미리 본다. 사진과 스타일을 부르는 HTML 은 묶음으로 보관돼 있고, iframe 이
 * 보관본 안의 파일 주소로 열려 상대 주소의 사진도 보관본에서 읽는다. 묶음은 zip 으로 내려받는다.
 * 사진은 그대로 보이고 CSS 는 내려받는다. 지우기는 두 번 눌러야 한다.
 */
export function VaultListView() {
  const [items, setItems] = useState<VaultItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 대화의 「보관함에서 보기」 가 `item` 으로 보관본 하나를 가리킨다. 그 줄의 미리보기를 펼친 채 연다.
  const linked = Number(useSearchParams().get("item"));
  const [previewId, setPreviewId] = useState<number | null>(
    Number.isInteger(linked) && linked > 0 ? linked : null,
  );
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const response = await fetchVaultItems();
      if (!response.ok) {
        setError(await describeFailure(response, VAULT_ERRORS));
        return;
      }
      setItems(((await response.json()) as VaultList).items);
      setError(null);
    } catch {
      setError("보관한 결과물을 불러오지 못했어요. 다시 시도해 주세요.");
    }
  }, []);

  useEffect(() => {
    // 처음 열 때 한 번 읽는다. 화면 상태는 읽은 뒤 채운다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  async function remove(id: number) {
    setBusyId(id);
    try {
      const response = await deleteVaultItem(id);
      if (!response.ok) {
        setError(await describeFailure(response, VAULT_ERRORS));
        return;
      }
      if (previewId === id) setPreviewId(null);
      setConfirmId(null);
      await load();
    } catch {
      setError("지우지 못했어요. 다시 시도해 주세요.");
    } finally {
      setBusyId(null);
    }
  }

  if (items === null && error === null)
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;

  return (
    <div className="space-y-3" data-testid="vault-list">
      {error ? (
        <Notice variant="error" role="alert">
          {error}
        </Notice>
      ) : null}
      {items !== null && items.length === 0 ? (
        <EmptyState
          title="보관한 결과물이 없어요"
          description="대화에서 에이전트가 만든 파일을 여기에 암호화해 둬요. 결과물을 열고 「보관」을 누르거나 에이전트에게 파일 공간에 넣어 달라고 하면 들어와요. 대화를 지워도 남아요."
        />
      ) : null}
      {items?.map((item) => {
        const isHtml = item.path.toLowerCase().endsWith(".html");
        const isImage = isVaultImage(item.path);
        // 비서가 만든 그림은 제목이 곧 파일 이름이라 확장자를 떼고 보인다. HTML 이 부르던 사진은 파일 이름 그대로다.
        // 비서가 붙인 제목이 있으면 그것을 먼저 보인다.
        const name =
          item.title?.trim() ||
          (isImage && item.path.startsWith("generated/")
            ? imageTitle(item.path)
            : artifactName(item.path));
        const isBundle = item.fileCount > 1;
        return (
          <div
            key={item.id}
            data-testid="vault-item"
            className="rounded-md border border-border p-3"
          >
            <div className="flex flex-wrap items-center gap-2">
              {isImage ? (
                // eslint-disable-next-line @next/next/no-img-element -- 보관본 주소는 next/image 최적화를 거치지 않는다
                <img
                  src={vaultContentUrl(item.id)}
                  alt=""
                  loading="lazy"
                  data-testid="vault-thumbnail"
                  className="size-10 shrink-0 rounded border border-border object-cover"
                />
              ) : null}
              <div className="min-w-0 flex-1">
                <p className="truncate font-medium" title={item.path}>
                  {name}
                </p>
                {item.description ? (
                  <p
                    data-testid="vault-description"
                    className="truncate text-xs text-muted-foreground"
                  >
                    {item.description}
                  </p>
                ) : null}
              </div>
              <span className="text-xs text-muted-foreground">
                {isBundle ? `파일 ${item.fileCount}개 · ` : ""}
                {formatSize(item.byteSize)} · {formatFullTime(item.vaultedAt)}
              </span>
              {isHtml || isImage ? (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setPreviewId(previewId === item.id ? null : item.id)
                  }
                >
                  {previewId === item.id ? "미리보기 닫기" : "미리보기"}
                </Button>
              ) : null}
              <Button variant="outline" size="sm" asChild>
                <a
                  href={
                    isBundle
                      ? vaultArchiveUrl(item.id)
                      : vaultContentUrl(item.id, true)
                  }
                >
                  {isBundle ? "묶음 내려받기" : "내려받기"}
                </a>
              </Button>
              {confirmId === item.id ? (
                <>
                  <Button
                    variant="destructive"
                    size="sm"
                    loading={busyId === item.id}
                    loadingText="지우는 중…"
                    onClick={() => void remove(item.id)}
                  >
                    정말 지우기
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busyId === item.id}
                    onClick={() => setConfirmId(null)}
                  >
                    취소
                  </Button>
                </>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setConfirmId(item.id)}
                >
                  지우기
                </Button>
              )}
            </div>
            {previewId === item.id ? (
              <VaultPreview item={item} name={name} />
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

/**
 * 보관본 하나의 미리보기다. 사진은 그대로 보이고 HTML 은 스크립트가 돌지 않는 iframe 으로 연다.
 *
 * <p>iframe 은 보관본 안의 파일 주소로 열어 HTML 이 상대 주소로 부르는 사진과 스타일도 같은 보관본에서 읽는다.
 */
function VaultPreview({ item, name }: { item: VaultItem; name: string }) {
  if (isVaultImage(item.path))
    return (
      // eslint-disable-next-line @next/next/no-img-element -- 보관본 주소는 next/image 최적화를 거치지 않는다
      <img
        src={vaultContentUrl(item.id)}
        alt={name}
        data-testid="vault-image"
        className="mt-3 max-h-96 rounded border border-border"
      />
    );
  return (
    // 에이전트가 만든 HTML 은 흰 바탕을 전제로 하므로 bg-white 를 그대로 둔다.
    <iframe
      title={name}
      sandbox={FRAME_SANDBOX}
      src={vaultFileUrl(item.id, item.path)}
      data-testid="vault-frame"
      className="mt-3 h-96 w-full rounded border border-border bg-white"
    />
  );
}
