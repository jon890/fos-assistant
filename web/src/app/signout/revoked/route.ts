import { redirect } from "next/navigation";
import { callControlPlane } from "@/lib/control-plane";

/**
 * 서버 컴포넌트가 꺼진 사용자를 넘기는 곳이다.
 *
 * <p>서버 컴포넌트는 화면을 그리는 중이라 쿠키를 지우지 못한다. Route Handler 는 지울 수 있으므로 여기서
 * Control Plane 을 한 번 더 부른다. 꺼진 사용자면 `callControlPlane` 이 응답을 받는 자리에서 세션 쿠키를
 * 지우고, 이어서 간 `/` 는 세션이 없어 `/signin` 으로 보낸다.
 *
 * <p>**이 주소가 스스로 세션을 지우지 않는다.** GET 이라 다른 사이트가 사용자를 이 주소로 보낼 수 있다.
 * 지울지는 Control Plane 의 답이 정하므로, 켜져 있는 사용자는 아무것도 지워지지 않고 `/` 로 돌아간다.
 */
export async function GET(): Promise<never> {
  await callControlPlane("/api/v1/me");
  redirect("/");
}
