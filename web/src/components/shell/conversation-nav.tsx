"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Ellipsis } from "lucide-react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  focusWithoutTooltip,
  TooltipButton,
} from "@/components/ui/tooltip-button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { useExit } from "@/components/ui/use-exit";
import { useConversations, type Conversation } from "./conversations-provider";
import { groupByDate } from "./group-by-date";
import { groupByTask } from "./group-by-task";
import { NavPending } from "./nav-pending";
import { cn } from "cn";

export function ConversationNav({
  onNavigate,
  query,
}: {
  onNavigate(href: string): void;
  query: string;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const {
    conversations,
    loading,
    error,
    hasMore,
    loadingMore,
    moreError,
    loadMore,
    wasPaged,
    rename,
    remove,
    drop,
    startNew,
  } = useConversations();
  const { exit } = useExit();
  /** 지우기 요청이 성공해 나가는 움직임을 보이는 줄이다 */
  const [leavingId, setLeavingId] = useState<string | null>(null);
  /** 목록을 처음 읽어 왔을 때 있던 대화들이다. 여기 없는 줄만 새 줄로 보고 등장 움직임을 준다 */
  const [initialIds, setInitialIds] = useState<Set<string> | null>(null);
  if (initialIds === null && !loading && !error)
    setInitialIds(new Set(conversations.map((item) => item.id)));
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<Conversation | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  /** 사용자가 펼친 작업 묶음이다 */
  const [expandedTasks, setExpandedTasks] = useState<Set<string>>(new Set());
  // 강제로 펼쳐진 줄도 사용자가 누르면 접히도록, 접은 작업을 따로 기억한다
  const [collapsedTasks, setCollapsedTasks] = useState<Set<string>>(new Set());
  const cancelledEdit = useRef(false);
  /** 메뉴에서 이름 바꾸기를 골랐다. 메뉴가 닫히며 초점을 메뉴 단추로 옮기면 입력칸이 blur 되어 편집이 끝나므로 그때는 막는다 */
  const renameChosen = useRef(false);
  /** 메뉴 바깥을 눌러 닫았다. 그때는 누른 쪽의 초점을 빼앗지 않는다 */
  const menuInteractedOutside = useRef(false);
  /** 지우기 창을 닫은 뒤 초점을 돌려줄 메뉴 단추다. 창은 메뉴 단추가 아니라 메뉴 항목에서 열리므로 직접 돌려준다 */
  const menuButtons = useRef(new Map<string, HTMLButtonElement>());
  const lastDeleteId = useRef<string | null>(null);
  const normalizedQuery = query.trim().toLocaleLowerCase("ko-KR");
  const visible = conversations.filter((item) =>
    (item.title || "새 대화")
      .toLocaleLowerCase("ko-KR")
      .includes(normalizedQuery),
  );
  const { tasks, others } = groupByTask(visible);
  const navRef = useRef<HTMLElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const searching = normalizedQuery !== "";
  const needsMore = hasMore && !loadingMore && moreError === null;

  /** 아직 읽지 않은 대화까지 검색하려면 남은 쪽을 모두 읽어 둬야 한다. 쪽당 최대치로 읽어 요청 수를 줄인다 */
  useEffect(() => {
    if (searching && needsMore) void loadMore(100);
  }, [searching, needsMore, loadMore]);

  /** 목록 끝에 닿으면 다음 쪽을 읽는다. 읽은 뒤에도 끝이 보이면(짧은 화면) 다시 닿은 것으로 쳐서 이어 읽는다 */
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (searching || !needsMore || sentinel === null) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) void loadMore();
      },
      { root: navRef.current, rootMargin: "200px" },
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [searching, needsMore, loadMore, conversations.length]);

  function beginEdit(conversation: Conversation) {
    renameChosen.current = true;
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
      setActionError(
        reason instanceof Error
          ? reason.message
          : "대화 이름을 바꾸지 못했어요.",
      );
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    const id = deleteTarget.id;
    setDeleting(true);
    try {
      await remove(id);
      setActionError(null);
      // 요청이 성공한 뒤에만 줄을 흐리게 하고 뺀다. 실패하면 줄이 그대로 남는다.
      setLeavingId(id);
      exit(() => {
        drop(id);
        setLeavingId(null);
        if (pathname === `/chat/${id}`) {
          startNew();
          router.push("/");
        }
      });
    } catch (reason) {
      setActionError(
        reason instanceof Error ? reason.message : "대화를 지우지 못했어요.",
      );
    } finally {
      // 성공이든 실패든 창을 닫는다. 실패한 까닭은 목록 위 알림에 보인다.
      setDeleting(false);
      setDeleteTarget(null);
    }
  }

  /** 대화 한 줄이다. 날짜 묶음과 작업 묶음이 같은 모양을 쓴다 */
  const renderRow = (conversation: Conversation) => {
    const title = conversation.title || "새 대화";
    return (
      <li
        key={conversation.id}
        data-leaving={leavingId === conversation.id || undefined}
        className={cn(
          "group relative flex min-w-0 items-center",
          initialIds !== null &&
            !initialIds.has(conversation.id) &&
            !wasPaged(conversation.id) &&
            "animate-message-assistant",
        )}
        onAnimationEnd={(event) => {
          // 한 번 움직인 줄은 처음 있던 줄로 친다. 검색으로 걸러졌다 다시 나타나도 다시 움직이지 않는다.
          // 줄 안의 다른 animation 이 끝난 것은 건너뛴다.
          if (event.target !== event.currentTarget) return;
          setInitialIds((ids) =>
            ids === null || ids.has(conversation.id)
              ? ids
              : new Set(ids).add(conversation.id),
          );
        }}
      >
        {editingId === conversation.id ? (
          <input
            autoFocus
            aria-label="대화 이름"
            data-rename-input=""
            value={editValue}
            onChange={(event) => setEditValue(event.target.value)}
            onBlur={() => void finishEdit(conversation)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                cancelledEdit.current = true;
                setEditingId(null);
              } else if (
                event.key === "Enter" &&
                !event.nativeEvent.isComposing
              ) {
                event.preventDefault();
                event.currentTarget.blur();
              }
            }}
            className="min-w-0 flex-1 rounded-md border border-border bg-background px-2 py-2 text-sm"
          />
        ) : (
          <Link
            href={`/chat/${conversation.id}`}
            // 보이는 줄마다 대화 화면을 미리 읽으면 목록이 길 때 요청이 한꺼번에 몰린다.
            prefetch={false}
            onClick={(event) => {
              // 사진을 먼저 올리며 주소만 바뀐 경우 같은 대화로 다시 이동하지 않는다.
              if (window.location.pathname === `/chat/${conversation.id}`)
                event.preventDefault();
              onNavigate(`/chat/${conversation.id}`);
            }}
            aria-current={
              pathname === `/chat/${conversation.id}` ? "page" : undefined
            }
            className={cn(
              "flex min-w-0 flex-1 items-center rounded-md px-2 py-2 text-sm hover:bg-accent",
              pathname === `/chat/${conversation.id}` &&
                "bg-accent font-medium",
            )}
            title={title}
          >
            <span className="min-w-0 flex-1 truncate">{title}</span>
            <NavPending />
          </Link>
        )}
        {/* 모달로 두면 열린 동안 body 의 pointer-events 가 꺼져, 메뉴 바깥을 누른 첫 클릭이 그 자리에 닿지 않는다. 바깥을 누르면 닫히는 것은 모달이 아니어도 같다. */}
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <TooltipButton
              label={`${title} 메뉴`}
              ref={(node) => {
                if (node) menuButtons.current.set(conversation.id, node);
                else menuButtons.current.delete(conversation.id);
              }}
              className={cn(
                "hover:bg-accent aria-expanded:bg-accent focus:opacity-100",
                "md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100 md:aria-expanded:opacity-100",
              )}
            >
              <Ellipsis aria-hidden="true" />
            </TooltipButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="end"
            aria-label={`${title} 메뉴`}
            onInteractOutside={() => {
              menuInteractedOutside.current = true;
            }}
            onCloseAutoFocus={(event) => {
              // Radix 의 기본 되돌리기는 Tooltip 을 연다. 직접 되돌리되 기본 동작처럼 바깥을 눌러 닫았으면
              // 그쪽 초점을 두고, 이름 바꾸기를 골랐으면 입력칸의 초점을 둔다.
              event.preventDefault();
              const keepFocus =
                renameChosen.current || menuInteractedOutside.current;
              renameChosen.current = false;
              menuInteractedOutside.current = false;
              if (!keepFocus)
                focusWithoutTooltip(menuButtons.current.get(conversation.id));
            }}
          >
            <DropdownMenuItem onSelect={() => beginEdit(conversation)}>
              이름 바꾸기
            </DropdownMenuItem>
            <DropdownMenuItem
              onSelect={() => {
                lastDeleteId.current = conversation.id;
                setDeleteTarget(conversation);
                setActionError(null);
              }}
            >
              지우기
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </li>
    );
  };

  return (
    <nav
      ref={navRef}
      aria-label="대화 목록"
      className="min-h-0 flex-1 overflow-y-auto px-2"
    >
      {actionError ? (
        <p role="alert" className="mb-2 px-2 text-sm text-destructive">
          {actionError}
        </p>
      ) : null}
      {loading ? (
        <div
          aria-label="대화 목록을 읽고 있어요"
          className="flex flex-col gap-2 px-1"
        >
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
          <Skeleton className="h-10" />
        </div>
      ) : error ? (
        <p className="px-2 text-sm text-muted-foreground">{error}</p>
      ) : visible.length === 0 ? (
        <p className="px-2 text-sm text-muted-foreground">
          {normalizedQuery ? "맞는 대화가 없어요" : "아직 대화가 없어요."}
        </p>
      ) : (
        <>
          {tasks.length > 0 ? (
            <section className="mb-5">
              <h2 className="px-2 py-2 text-xs font-medium text-muted-foreground">
                예약 작업
              </h2>
              <ol className="flex flex-col gap-0.5">
                {tasks.map((group) => {
                  const open =
                    !collapsedTasks.has(group.taskId) &&
                    (searching ||
                      expandedTasks.has(group.taskId) ||
                      group.conversations.some(
                        (item) => pathname === `/chat/${item.id}`,
                      ));
                  return (
                    <li key={group.taskId}>
                      <button
                        type="button"
                        data-testid="task-group"
                        aria-expanded={open}
                        onClick={() => {
                          const toggle = (current: Set<string>) => {
                            const next = new Set(current);
                            if (next.has(group.taskId))
                              next.delete(group.taskId);
                            else next.add(group.taskId);
                            return next;
                          };
                          if (open) setCollapsedTasks(toggle);
                          else {
                            setCollapsedTasks((current) => {
                              const next = new Set(current);
                              next.delete(group.taskId);
                              return next;
                            });
                            setExpandedTasks((current) =>
                              new Set(current).add(group.taskId),
                            );
                          }
                        }}
                        className="flex w-full min-w-0 items-center rounded-md px-2 py-2 text-left text-sm hover:bg-accent"
                      >
                        <span className="min-w-0 flex-1 truncate">
                          {group.title || "예약 작업"}
                        </span>
                        <span className="ml-2 shrink-0 text-xs text-muted-foreground">
                          {group.conversations.length}
                        </span>
                      </button>
                      {open ? (
                        <ol className="ml-3 flex flex-col gap-0.5">
                          {group.conversations.map(renderRow)}
                        </ol>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
            </section>
          ) : null}
          {groupByDate(others, new Date()).map((group) => (
            <section key={group.label} className="mb-5">
              <h2 className="px-2 py-2 text-xs font-medium text-muted-foreground">
                {group.label}
              </h2>
              <ol className="flex flex-col gap-0.5">
                {group.items.map(renderRow)}
              </ol>
            </section>
          ))}
        </>
      )}
      {!loading && !error && hasMore ? (
        <div
          ref={sentinelRef}
          className="px-2 pb-3 text-sm text-muted-foreground"
        >
          {moreError ? (
            <p role="alert">
              {moreError}{" "}
              <button
                type="button"
                className="underline"
                onClick={() => void loadMore()}
              >
                다시 읽기
              </button>
            </p>
          ) : loadingMore || searching ? (
            <Skeleton aria-label="대화를 더 읽고 있어요" className="h-10" />
          ) : null}
        </div>
      ) : null}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open && !deleting) setDeleteTarget(null);
        }}
      >
        <AlertDialogContent
          onEscapeKeyDown={(event) => {
            if (deleting) event.preventDefault();
          }}
          onCloseAutoFocus={(event) => {
            const id = lastDeleteId.current;
            const button =
              id === null ? undefined : menuButtons.current.get(id);
            if (!button) return;
            event.preventDefault();
            focusWithoutTooltip(button);
          }}
        >
          <AlertDialogTitle>대화 지우기</AlertDialogTitle>
          <AlertDialogDescription className="text-foreground">
            {deleteTarget?.title || "새 대화"} 를 목록에서 지워요. 사용량 기록은
            남아요.
          </AlertDialogDescription>
          <AlertDialogFooter>
            {/* AlertDialogCancel 로 두어야 Radix 가 창을 열 때 「취소」 에 초점을 준다. */}
            <AlertDialogCancel asChild>
              <Button variant="outline" disabled={deleting}>
                취소
              </Button>
            </AlertDialogCancel>
            <Button
              variant="destructive"
              loading={deleting}
              loadingText="지우는 중"
              onClick={() => void confirmDelete()}
            >
              지우기
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </nav>
  );
}
