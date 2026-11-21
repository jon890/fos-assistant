import Link from "next/link";
import { PageFrame } from "@/components/ui/page-frame";
import { PAGE_HEADS } from "@/lib/page-head";

/** 연결 하나 화면의 뒤로 가기다. 목록 화면으로 돌아간다. */
export const CONNECTION_BACK = {
  href: "/connections",
  label: PAGE_HEADS.connections.title,
};

/** 모르는 id 나 형식이 틀린 id 로 열었을 때의 화면이다. */
export function ConnectorNotFound() {
  return (
    <PageFrame title="찾을 수 없는 서비스예요" back={CONNECTION_BACK}>
      <Link
        href="/connections"
        data-testid="connector-not-found"
        className="text-sm text-foreground underline underline-offset-4"
      >
        서비스 연결로 돌아가기
      </Link>
    </PageFrame>
  );
}
