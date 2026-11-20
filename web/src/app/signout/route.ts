import { signOut } from "@/auth";

/**
 * 이름 메뉴의 「로그아웃」 이 부른다(docs/features/app-shell/design.md 의 「로그아웃」).
 *
 * <p>이 브라우저의 세션 쿠키만 지운다. Auth.js 는 JWT 세션이라 다른 기기의 로그인과 Control Plane 의 데이터는
 * 그대로다. 이동은 브라우저가 문서째 하므로 여기서는 204 만 돌려준다.
 *
 * <p>서버 동작으로 만들지 않는다. 쿠키를 바꾼 서버 동작은 그 응답에서 지금 경로를 다시 그려 page 의
 * `redirect("/signin")` 과 문서 이동이 겹치고, 배포 뒤 열려 있던 탭은 서버 동작을 찾지 못해 실패한다.
 * 세션 쿠키가 SameSite=Lax 라 다른 사이트가 보낸 POST 에는 쿠키가 붙지 않는다.
 */
export async function POST(): Promise<Response> {
  try {
    await signOut({ redirect: false });
  } catch (error) {
    console.error("로그아웃하지 못했다", error);
    return new Response(null, { status: 500 });
  }
  return new Response(null, { status: 204 });
}
