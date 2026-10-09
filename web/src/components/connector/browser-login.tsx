import Link from "next/link";
import { Notice } from "@/components/ui/notice";
import { browserLoginHref, type ConnectionFailure } from "@/lib/connection";

function LoginLink({ href }: { href: string }) {
  return (
    <Link
      prefetch={false}
      href={href}
      className="shrink-0 text-foreground underline underline-offset-4"
      data-testid="browser-login-link"
    >
      내 브라우저에서 로그인
    </Link>
  );
}

/** 「내 브라우저」 에 로그인한 계정을 쓰는 커넥터면 칸 위에 먼저 로그인하라고 안내한다. */
export function LoginNotice({ url }: { url: string | null | undefined }) {
  const href = browserLoginHref(url);
  if (!href) return null;
  return (
    <Notice variant="info" data-testid="browser-login-notice">
      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span>
          이 커넥터는 「내 브라우저」에 로그인한 계정을 써요. 먼저 내
          브라우저에서 로그인한 뒤 연결해 주세요.
        </span>
        <LoginLink href={href} />
      </span>
    </Notice>
  );
}

/**
 * 연결 화면의 오류 상자다. 글만 받은 오류는 그대로 보인다.
 * 연결 확인이나 등록이 값을 확인하지 못해 실패했고 로그인할 곳이 있으면 문구 옆에 로그인 링크를 둔다.
 */
export function ErrorNotice({
  error,
  loginUrl,
}: {
  error: string | ConnectionFailure | null;
  loginUrl: string | null | undefined;
}) {
  if (!error) return null;
  if (typeof error === "string")
    return (
      <Notice variant="error" role="alert">
        {error}
      </Notice>
    );
  const href =
    error.code === "CONNECTOR_CREDENTIAL_REJECTED"
      ? browserLoginHref(loginUrl)
      : null;
  return (
    <Notice variant="error" role="alert">
      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span>{error.message}</span>
        {href ? <LoginLink href={href} /> : null}
      </span>
    </Notice>
  );
}
