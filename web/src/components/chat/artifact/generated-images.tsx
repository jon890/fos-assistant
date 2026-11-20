"use client";

import Link from "next/link";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { describeFailure } from "@/components/error-message";
import { imageTitle } from "@/lib/artifact-name";
import { VAULT_ERRORS, vaultArtifact } from "@/lib/artifact-vault-api";
import type { MessageArtifact } from "@/components/chat/message-types";
import { ArtifactVaultStatus } from "./artifact-vault-status";

/** 대화 결과물 폴더 안의 그림을 웹 서버 라우트로 받는 주소다. 조각마다 따로 싼다. */
function imageUrl(conversationId: string, path: string): string {
  return `/api/chat/conversations/${conversationId}/files/${path.split("/").map(encodeURIComponent).join("/")}`;
}

function extensionOf(path: string): string {
  const dot = path.lastIndexOf(".");
  return dot < 0 ? "" : path.slice(dot);
}

/**
 * 접힌 채 있다가 누르면 비서가 이미지 도구에 넘긴 지시문과 참고한 링크를 보인다. 둘 다 없으면 그리지 않는다.
 * 링크는 새 창으로 열고 이 화면의 정보를 넘기지 않는다. `https://` 로 시작하지 않는 값은 링크로 만들지 않는다.
 */
function PromptDetails({
  prompt,
  sourceLinks,
}: {
  prompt?: string | null;
  sourceLinks?: string[];
}) {
  const links = sourceLinks ?? [];
  if (!prompt && links.length === 0) return null;
  return (
    <details data-testid="generated-image-prompt" className="mt-1 text-xs">
      <summary className="cursor-pointer text-muted-foreground underline-offset-4 hover:underline">
        그림 지시문 보기
      </summary>
      {prompt ? (
        <p className="mt-1 whitespace-pre-wrap break-words rounded-md bg-muted p-2 leading-5">
          {prompt}
        </p>
      ) : null}
      {links.length > 0 ? (
        <div
          data-testid="generated-image-source-links"
          className="mt-1 rounded-md bg-muted p-2 leading-5"
        >
          <p className="text-muted-foreground">참고한 링크</p>
          <ul className="mt-0.5 space-y-0.5">
            {links.map((link, index) => (
              <li key={`${index}-${link}`} className="break-all">
                {link.startsWith("https://") ? (
                  <a
                    href={link}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="underline underline-offset-4"
                  >
                    {link}
                  </a>
                ) : (
                  link
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </details>
  );
}

/** 크게 보기 화면의 「보관」 단추다. 보관본이 없는 그림에만 그린다. 보관하면 「내 파일」 에 남는다. */
function KeepButton({
  conversationId,
  path,
  kept,
  onKept,
}: {
  conversationId: string;
  path: string;
  /** 이 화면에서 이미 보관했다. 크게 보기를 닫았다 열어도 단추를 다시 보이지 않는다 */
  kept: boolean;
  onKept(): void;
}) {
  const [state, setState] = useState<"idle" | "busy">("idle");
  const [message, setMessage] = useState<string | null>(null);

  async function keep() {
    setState("busy");
    setMessage(null);
    try {
      const response = await vaultArtifact(conversationId, path);
      if (!response.ok) {
        setMessage(await describeFailure(response, VAULT_ERRORS));
        setState("idle");
        return;
      }
      setState("idle");
      onKept();
    } catch {
      setMessage("보관하지 못했어요. 다시 시도해 주세요.");
      setState("idle");
    }
  }

  if (kept)
    return (
      <span
        data-testid="generated-image-kept"
        className="text-xs text-muted-foreground"
      >
        보관했어요
      </span>
    );
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        loading={state === "busy"}
        loadingText="보관하는 중…"
        onClick={() => void keep()}
      >
        보관
      </Button>
      {message ? (
        <span role="alert" className="text-xs text-destructive">
          {message}
        </span>
      ) : null}
    </>
  );
}

/**
 * 답 안에 비서가 만든 그림을 그린다(스펙 generated-image REQ1~REQ3, REQ5, REQ7, REQ9).
 *
 * <p>그림은 만든 순서대로 나란히 보이고, 누르면 화면 가득 크게 보인다. 크게 보기에는 제목과 「내려받기」, 지시문, 보관 상태가 있다.
 * 보관 기간이 지나 원본이 지워진 그림은 안내와, 보관본이 있으면 「내 파일에서 보기」 를 보인다. 지시문은 그대로 남는다.
 */
export function GeneratedImages({
  images,
  conversationId,
  messageId,
}: {
  images: MessageArtifact[];
  conversationId: string | null;
  /** 저장한 답의 번호다. 아직 저장하지 않은 답이면 자동 보관 상태를 그리지 않는다 */
  messageId: number | string;
}) {
  const [openPath, setOpenPath] = useState<string | null>(null);
  // 메시지를 다시 읽기 전에는 보관본 번호가 오지 않는다. 그동안 보관한 그림을 기억한다.
  const [keptPaths, setKeptPaths] = useState<string[]>([]);
  if (images.length === 0 || conversationId === null) return null;
  const opened = images.find((image) => image.path === openPath) ?? null;
  return (
    <div
      data-testid="generated-images"
      className="mt-2 grid gap-3 sm:grid-cols-2"
    >
      {images.map((image) => {
        const title = imageTitle(image.path);
        return (
          <figure
            key={image.path}
            data-testid="generated-image"
            className="min-w-0"
          >
            {image.deleted ? (
              <div className="rounded-md border border-border p-3 text-sm text-muted-foreground">
                <p className="font-medium text-foreground">{title}</p>
                <p>보관 기간이 지나 대화에서는 볼 수 없어요.</p>
                {image.vaultItemId ? (
                  <Link
                    href={`/files?item=${image.vaultItemId}`}
                    className="underline underline-offset-4"
                  >
                    내 파일에서 보기
                  </Link>
                ) : null}
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setOpenPath(image.path)}
                aria-label={`${title} 크게 보기`}
                className="block w-full overflow-hidden rounded-md border border-border"
              >
                {/* eslint-disable-next-line @next/next/no-img-element -- 결과물 주소는 로그인 쿠키로 받는 내부 주소라 next/image 최적화를 거치지 않는다 */}
                <img
                  src={imageUrl(conversationId, image.path)}
                  alt={title}
                  loading="lazy"
                  className="h-auto w-full"
                />
              </button>
            )}
            <figcaption className="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground">
              {image.deleted ? null : (
                <span className="min-w-0 truncate">{title}</span>
              )}
              {typeof messageId === "number" ? (
                <ArtifactVaultStatus
                  conversationId={conversationId}
                  messageId={messageId}
                  path={image.path}
                  state={image.autoVault ?? null}
                  error={image.autoVaultError ?? null}
                />
              ) : null}
            </figcaption>
            <PromptDetails
              prompt={image.prompt}
              sourceLinks={image.sourceLinks}
            />
          </figure>
        );
      })}
      <Dialog
        open={opened !== null}
        onOpenChange={(open) => {
          if (!open) setOpenPath(null);
        }}
      >
        {opened ? (
          <DialogContent
            data-testid="generated-image-viewer"
            closeLabel="크게 보기 닫기"
            className="flex max-h-[calc(100dvh-2rem)] flex-col gap-3 sm:max-w-[min(64rem,calc(100%-2rem))]"
          >
            <DialogTitle className="pr-8">
              {imageTitle(opened.path)}
            </DialogTitle>
            <DialogDescription className="sr-only">
              비서가 만든 그림을 크게 보고 내려받는다
            </DialogDescription>
            {/* 휴대폰에서 두 손가락으로 키운 그림을 밀어 볼 수 있게 그림 칸만 스크롤한다. */}
            <div className="min-h-0 flex-1 touch-pinch-zoom overflow-auto">
              {/* eslint-disable-next-line @next/next/no-img-element -- 결과물 주소는 로그인 쿠키로 받는 내부 주소라 next/image 최적화를 거치지 않는다 */}
              <img
                src={imageUrl(conversationId, opened.path)}
                alt={imageTitle(opened.path)}
                className="mx-auto h-auto max-w-full"
              />
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" asChild>
                <a
                  href={imageUrl(conversationId, opened.path)}
                  download={`${imageTitle(opened.path)}${extensionOf(opened.path)}`}
                  data-testid="generated-image-download"
                >
                  내려받기
                </a>
              </Button>
              {opened.vaultItemId ? (
                <Link
                  href={`/files?item=${opened.vaultItemId}`}
                  className="text-xs underline underline-offset-4"
                >
                  내 파일에서 보기
                </Link>
              ) : opened.autoVault === "PENDING" ||
                opened.autoVault === "RUNNING" ? null : (
                <KeepButton
                  key={opened.path}
                  conversationId={conversationId}
                  path={opened.path}
                  kept={keptPaths.includes(opened.path)}
                  onKept={() =>
                    setKeptPaths((paths) => [...paths, opened.path])
                  }
                />
              )}
            </div>
            <PromptDetails
              prompt={opened.prompt}
              sourceLinks={opened.sourceLinks}
            />
          </DialogContent>
        ) : null}
      </Dialog>
    </div>
  );
}
