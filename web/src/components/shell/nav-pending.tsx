"use client";

import { useLinkStatus } from "next/link";
import { useEffect } from "react";

/** `Sidebar` 의 `role="status"` 영역에 옮기는 중임을 알리는 사건 이름이다. */
export const NAV_PENDING_EVENT = "nav-pending";

/**
 * `<Link>` 의 자식으로 두면 그 링크의 이동이 끝나지 않은 동안 작은 회전 표시를 보인다.
 *
 * <p>낭독기 안내는 이 컴포넌트가 직접 그리지 않는다. 사이드바 링크마다 붙는 `NavPending` 이
 * 저마다 `role="status"` 를 두면 낭독기가 같은 말을 여러 번 읽는다. 대신 `window` 로 옮기는 중
 * 여부를 알리고, `Sidebar` 하나가 받아 사이드바에 하나뿐인 안내 영역을 채운다.
 */
export function NavPending() {
  const { pending } = useLinkStatus();

  useEffect(() => {
    if (!pending) return;
    window.dispatchEvent(new CustomEvent<boolean>(NAV_PENDING_EVENT, { detail: true }));
    // 이동이 끝나 pending 이 거짓이 될 때와 컴포넌트가 사라질 때 모두 여기서 한 번만 끝났다고 알린다.
    // 켤 때 한 번, 끌 때 한 번씩만 보내야 사이드바가 세는 수가 어긋나지 않는다.
    return () => {
      window.dispatchEvent(new CustomEvent<boolean>(NAV_PENDING_EVENT, { detail: false }));
    };
  }, [pending]);

  if (!pending) return null;
  return (
    <span
      aria-hidden="true"
      data-testid="nav-pending"
      className="ml-2 inline-block h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none"
    />
  );
}
