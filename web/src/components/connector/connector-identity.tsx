import { ExternalLink, Plug } from "lucide-react";
import { CardDescription, CardTitle } from "@/components/ui/card";
import {
  connectorIconSrc,
  connectorLinkHref,
  type ConnectorSummary,
} from "@/lib/connection";
import { cn } from "cn";

/** 커넥터 아이콘이다. 값이 없거나 모양이 틀리면 기본 아이콘을 그린다. `<img>` 로만 그려 SVG 안의 스크립트가 돌지 않게 한다. */
export function ConnectorIcon({
  icon,
  className,
}: {
  icon: string | null | undefined;
  className?: string;
}) {
  const src = connectorIconSrc(icon);
  if (src) {
    return (
      <img
        data-testid="connector-icon"
        src={src}
        alt=""
        className={cn("size-10 shrink-0 rounded-md object-contain", className)}
      />
    );
  }
  return (
    <span
      className={cn(
        "flex size-10 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground",
        className,
      )}
    >
      <Plug
        data-testid="connector-icon-default"
        aria-hidden
        className="size-5"
      />
    </span>
  );
}

/** 서비스 소개 링크다. 카드마다 이름이 같으면 화면 낭독기가 구분하지 못해 제목을 덧붙인다. */
export function ConnectorLink({
  link,
  title,
  className,
}: {
  link: string | null | undefined;
  title: string;
  className?: string;
}) {
  const href = connectorLinkHref(link);
  if (!href) return null;
  return (
    <a
      data-testid="connector-link"
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(
        "inline-flex items-center gap-1 text-sm underline underline-offset-2",
        className,
      )}
    >
      사이트 열기
      <ExternalLink aria-hidden className="size-3.5" />
      <span className="sr-only">({title}, 새 탭)</span>
    </a>
  );
}

/** 상세 화면의 머리다. 아이콘, 이름, 설명, 링크를 한 묶음으로 그린다. */
export function ConnectorHeading({
  connector,
  title,
}: {
  connector: ConnectorSummary | null | undefined;
  title: string;
}) {
  return (
    <div className="flex min-w-0 items-start gap-3">
      <ConnectorIcon icon={connector?.icon} />
      <div className="min-w-0 space-y-1 break-words">
        <CardTitle>{title}</CardTitle>
        {connector?.description ? (
          <CardDescription>{connector.description}</CardDescription>
        ) : null}
        <ConnectorLink link={connector?.link} title={title} />
      </div>
    </div>
  );
}
