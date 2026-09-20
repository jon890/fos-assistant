import NextAuth from "next-auth";
import Google from "next-auth/providers/google";
import { isSignInAllowed } from "@/lib/control-plane";

/**
 * 로그인 화면을 그려 놓고 동작하지 않는 대신 첫 요청에서 실패한다.
 *
 * <p>compose 파일이 아니라 여기 두는 이유는, Google 클라이언트가 생기기 전에도 백엔드를 배포하고
 * 확인할 수 있어야 하기 때문이다. compose 에 필수 변수 검사를 두면 그 파일 전체가 뜨지 않는다.
 */
function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value || value.trim().length === 0) {
    throw new Error(`${name} is not set; the web tier cannot sign anyone in without it`);
  }
  return value;
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Google({
      clientId: requireEnv("AUTH_GOOGLE_ID"),
      clientSecret: requireEnv("AUTH_GOOGLE_SECRET"),
    }),
  ],
  callbacks: {
    /**
     * 누가 들어올 수 있는지는 Control Plane 의 허용 목록이 정한다.
     *
     * <p>목록이 실행 중에 바뀌므로 이 판정을 매번 묻는다. Control Plane 이 답하지 않으면 거짓을
     * 돌려준다. 판정하지 못하는 동안 들여보내지 않는다.
     */
    async signIn({ profile }) {
      const email = profile?.email;
      if (!email) return false;
      return isSignInAllowed(email);
    },
  },
  pages: { signIn: "/signin" },
});
