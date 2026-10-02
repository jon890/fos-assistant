import { unstable_rethrow } from "next/navigation";
import { callControlPlane } from "@/lib/control-plane";

export type Me = {
  id: number;
  email: string;
  displayName: string;
  role: "ADMIN" | "MEMBER";
};

export async function readMe(): Promise<Me | null> {
  try {
    const result = await callControlPlane<Me>("/api/v1/me");
    return result.ok ? result.data : null;
  } catch (error) {
    // 꺼진 사용자를 로그인 화면으로 보내는 `redirect` 는 삼키지 않고 그대로 올려 보낸다.
    unstable_rethrow(error);
    return null;
  }
}
