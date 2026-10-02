import { cache } from "react";
import { redirect, unstable_rethrow } from "next/navigation";
import { callControlPlane } from "@/lib/control-plane";

export type Me = {
  id: number;
  email: string;
  displayName: string;
  role: "ADMIN" | "MEMBER";
};

/** 한 요청 안에서는 레이아웃과 page 가 같이 불러도 `/api/v1/me` 를 한 번만 읽는다. */
export const readMe = cache(async (): Promise<Me | null> => {
  try {
    const result = await callControlPlane<Me>("/api/v1/me");
    return result.ok ? result.data : null;
  } catch (error) {
    // 꺼진 사용자를 로그인 화면으로 보내는 `redirect` 는 삼키지 않고 그대로 올려 보낸다.
    unstable_rethrow(error);
    return null;
  }
});

/**
 * 관리자 영역의 page 가 자기 데이터를 읽기 전에 부른다. `MEMBER` 역할이면 홈으로 넘긴다.
 *
 * <p>레이아웃과 page 는 나란히 그려져 레이아웃의 판정만으로는 page 의 조회를 막지 못한다.
 * 역할을 읽지 못했으면 넘기지 않는다. 그때는 레이아웃이 안내를 보인다.
 */
export async function redirectMemberHome(): Promise<void> {
  const me = await readMe();
  if (me !== null && me.role !== "ADMIN") redirect("/");
}
