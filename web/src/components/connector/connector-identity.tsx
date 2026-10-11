import { ExternalLink, Plug } from "lucide-react";
import { CardDescription, CardTitle } from "@/components/ui/card";
import {
  connectorIconSrc,
  connectorAppearanceLinkHref,
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
  label = "공식 사이트",
  readme = false,
  className,
}: {
  link: string | null | undefined;
  title: string;
  label?: string;
  readme?: boolean;
  className?: string;
}) {
  const href = connectorAppearanceLinkHref(link);
  if (!href) return null;
  return (
    <a
      data-testid={readme ? "connector-readme-link" : "connector-link"}
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(
        "inline-flex min-w-0 items-center gap-1 rounded-sm text-sm underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-ring",
        className,
      )}
    >
      {label}
      <ExternalLink aria-hidden className="size-3.5 shrink-0" />
      <span className="sr-only">({title}, 새 탭)</span>
    </a>
  );
}

/** 공식 서비스나 프로젝트와 커넥터 설명서를 서로 다른 이름으로 연다. */
export function ConnectorLinks({
  connector,
  title,
  className,
}: {
  connector: ConnectorSummary | null | undefined;
  title: string;
  className?: string;
}) {
  if (
    !connectorAppearanceLinkHref(connector?.link) &&
    !connectorAppearanceLinkHref(connector?.readmeUrl)
  )
    return null;
  return (
    <div className={cn("flex min-w-0 flex-wrap gap-x-3 gap-y-1", className)}>
      <ConnectorLink
        link={connector?.link}
        title={title}
        label={
          connector?.linkKind === "project" ? "프로젝트 GitHub" : "공식 사이트"
        }
      />
      <ConnectorLink
        link={connector?.readmeUrl}
        title={title}
        label="커넥터 README"
        readme
      />
    </div>
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
        <ConnectorLinks connector={connector} title={title} />
      </div>
    </div>
  );
}
