import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { Notice } from "@/components/ui/notice";
import { readMe } from "@/lib/me";

/**
 * 관리자 영역의 문이다. `ADMIN` 역할만 아래 화면을 본다.
 *
 * <p>역할을 읽지 못했으면 넘기지 않는다. `MEMBER` 로 쳐서 넘기면 관리자가 까닭 없이 일반 화면으로 돌아간다.
 */
export default async function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const me = await readMe();
  if (me === null) {
    return (
      <Notice variant="error" role="alert">
        계정 정보를 읽지 못했어요. 잠시 뒤 다시 열어 주세요.
      </Notice>
    );
  }
  if (me.role !== "ADMIN") redirect("/");

  return children;
}
