"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { useConversations, type Conversation } from "./conversations-provider";
import { groupByDate } from "./group-by-date";

export function ConversationNav({ onNavigate, query }: { onNavigate(): void; query: string }) {
  const pathname = usePathname();
  const router = useRouter();
  const { conversations, loading, error, rename, remove, startNew } = useConversations();
  const [openMenu, setOpenMenu] = useState<number | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editValue, setEditValue] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<Conversation | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const cancelledEdit = useRef(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const menuButtons = useRef(new Map<number, HTMLButtonElement>());
  const normalizedQuery = query.trim().toLocaleLowerCase("ko-KR");
  const visible = conversations.filter((item) =>
    (item.title || "새 대화").toLocaleLowerCase("ko-KR").includes(normalizedQuery));

  useEffect(() => {
    if (openMenu === null) return;
    const closeOutside = (event: PointerEvent) => {
      const row = menuButtons.current.get(openMenu)?.parentElement;
      if (event.target instanceof Node && !row?.contains(event.target)) setOpenMenu(null);
    };
    document.addEventListener("pointerdown", closeOutside);
    return () => document.removeEventListener("pointerdown", closeOutside);
  }, [openMenu]);

  useEffect(() => {
    if (openMenu === null) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing || event.defaultPrevented) return;
      event.preventDefault();
      event.stopPropagation();
      menuButtons.current.get(openMenu)?.focus();
      setOpenMenu(null);
    };
    window.addEventListener("keydown", closeOnEscape, true);
    return () => window.removeEventListener("keydown", closeOnEscape, true);
  }, [openMenu]);

  function beginEdit(conversation: Conversation) {
    setOpenMenu(null);
    setEditingId(conversation.id);
    setEditValue(conversation.title);
    setActionError(null);
    cancelledEdit.current = false;
  }

  async function finishEdit(conversation: Conversation) {
    if (cancelledEdit.current) return;
    setEditingId(null);
    const title = editValue.trim();
    if (!title || title === conversation.title) return;
    try {
      await rename(conversation.id, title);
      setActionError(null);
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "대화 이름을 바꾸지 못했다.");
    }
  }

  function closeDialog() {
    dialogRef.current?.close();
    const target = deleteTarget;
    setDeleteTarget(null);
    if (target) requestAnimationFrame(() => menuButtons.current.get(target.id)?.focus());
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    const id = deleteTarget.id;
    try {
      await remove(id);
      setActionError(null);
      closeDialog();
      if (pathname === `/c/${id}`) {
        startNew();
        router.push("/");
      }
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "대화를 지우지 못했다.");
      closeDialog();
    }
  }

  return (
    <nav aria-label="대화 목록" className="min-h-0 flex-1 overflow-y-auto px-2">
      {actionError ? <p role="alert" className="mb-2 px-2 text-sm text-danger">{actionError}</p> : null}
      {loading ? (
        <div aria-label="대화 목록을 읽는 중" className="flex flex-col gap-2 px-1">
          <Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" />
        </div>
      ) : error ? (
        <p className="px-2 text-sm text-muted">{error}</p>
      ) : visible.length === 0 ? (
        <p className="px-2 text-sm text-muted">{normalizedQuery ? "맞는 대화가 없다" : "아직 대화가 없다."}</p>
      ) : (
        groupByDate(visible, new Date()).map((group) => (
          <section key={group.label} className="mb-5">
            <h2 className="px-2 py-2 text-xs font-medium text-muted">{group.label}</h2>
            <ol className="flex flex-col gap-0.5">
              {group.items.map((conversation) => {
                const title = conversation.title || "새 대화";
                return <li key={conversation.id} className="group relative flex min-w-0 items-center">
                  {editingId === conversation.id ? (
                    <input autoFocus aria-label="대화 이름" value={editValue}
                      onChange={(event) => setEditValue(event.target.value)}
                      onBlur={() => void finishEdit(conversation)}
                      onKeyDown={(event) => {
                        if (event.key === "Escape") {
                          event.preventDefault();
                          event.stopPropagation();
                          cancelledEdit.current = true;
                          setEditingId(null);
                        } else if (event.key === "Enter" && !event.nativeEvent.isComposing) {
                          event.preventDefault();
                          event.currentTarget.blur();
                        }
                      }}
                      className="min-w-0 flex-1 rounded-md border border-border bg-background px-2 py-2 text-sm" />
                  ) : (
                    <Link href={`/c/${conversation.id}`} onClick={(event) => {
                      // 사진을 먼저 올리며 주소만 바뀐 경우 같은 대화로 다시 이동하지 않는다.
                      if (window.location.pathname === `/c/${conversation.id}`) event.preventDefault();
                      onNavigate();
                    }}
                      aria-current={pathname === `/c/${conversation.id}` ? "page" : undefined}
                      className={`min-w-0 flex-1 truncate rounded-md px-2 py-2 text-sm hover:bg-surface-raised ${
                        pathname === `/c/${conversation.id}` ? "bg-surface-raised font-medium" : ""
                      }`}
                      title={title}>{title}</Link>
                  )}
                  <button type="button" aria-label={`${title} 메뉴`} aria-expanded={openMenu === conversation.id}
                    ref={(node) => { if (node) menuButtons.current.set(conversation.id, node); else menuButtons.current.delete(conversation.id); }}
                    onClick={() => setOpenMenu(openMenu === conversation.id ? null : conversation.id)}
                    className="rounded-md px-2 py-1 text-sm hover:bg-surface-raised focus:opacity-100 md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100">⋯</button>
                  {openMenu === conversation.id ? <div role="menu" aria-label={`${title} 메뉴`}
                    className="absolute right-0 top-full z-10 min-w-32 rounded-md border border-border bg-background p-1 shadow-lg">
                    <button type="button" role="menuitem" onClick={() => beginEdit(conversation)}
                      className="block w-full rounded px-2 py-1.5 text-left text-sm hover:bg-surface-raised">이름 바꾸기</button>
                    <button type="button" role="menuitem" onClick={() => {
                      setOpenMenu(null);
                      setDeleteTarget(conversation);
                      setActionError(null);
                      requestAnimationFrame(() => dialogRef.current?.showModal());
                    }} className="block w-full rounded px-2 py-1.5 text-left text-sm hover:bg-surface-raised">지우기</button>
                  </div> : null}
                </li>;
              })}
            </ol>
          </section>
        ))
      )}
      <dialog ref={dialogRef} aria-label="대화 지우기"
        onCancel={(event) => { event.preventDefault(); event.stopPropagation(); closeDialog(); }}
        onKeyDown={(event) => { if (event.key === "Escape") {
          event.preventDefault(); event.stopPropagation(); closeDialog();
        } }}
        className="m-auto max-w-[calc(100%-2rem)] rounded-lg border border-border bg-background p-6 text-foreground shadow-xl backdrop:bg-foreground/40">
        <p className="mb-5 text-sm">{deleteTarget?.title || "새 대화"} 를 목록에서 지운다. 사용량 기록은 남는다.</p>
        <div className="flex justify-end gap-2">
          <button type="button" onClick={closeDialog} className="rounded-md border border-border px-3 py-2 text-sm">취소</button>
          <button type="button" onClick={() => void confirmDelete()} className="rounded-md bg-danger px-3 py-2 text-sm text-white">지우기</button>
        </div>
      </dialog>
    </nav>
  );
}
