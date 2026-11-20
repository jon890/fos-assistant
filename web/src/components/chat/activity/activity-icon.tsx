import {
  Brain,
  CircleCheckBig,
  FolderCog,
  Gauge,
  Globe,
  Image,
  ListTodo,
  MessageCircle,
  PenLine,
  Plug,
  Save,
  Search,
  Sparkles,
  Users,
  Wrench,
  type LucideIcon,
} from "lucide-react";
import { cn } from "cn";
import { HELPER_KIND, toolKind, type ToolKindId } from "@/lib/tool-kind";
import type { ActivityItem } from "./activity-state";

/** 작업 과정의 종류마다 보이는 아이콘이다. 종류는 `tool-kind.ts` 가 정한다. */
const ICONS: Record<ToolKindId, LucideIcon> = {
  search: Search,
  page: Globe,
  memory: Brain,
  files: FolderCog,
  image: Image,
  artifact: Save,
  "follow-up": ListTodo,
  skill: Sparkles,
  draft: PenLine,
  "order-limit": Gauge,
  approval: CircleCheckBig,
  helper: Users,
  connector: Plug,
  other: Wrench,
};

/** 항목 하나의 종류 아이콘이다. 비서 안내는 말풍선이다. 낭독기에는 읽히지 않는다. */
export function ActivityKindIcon({
  item,
  className,
}: {
  item: ActivityItem;
  className?: string;
}) {
  const Icon =
    item.kind === "interim"
      ? MessageCircle
      : ICONS[
          item.kind === "subagent"
            ? HELPER_KIND.id
            : toolKind(item.name, item.connector).id
        ];
  return (
    <Icon
      aria-hidden="true"
      data-testid="activity-kind-icon"
      className={cn("size-3.5 shrink-0", className)}
    />
  );
}
