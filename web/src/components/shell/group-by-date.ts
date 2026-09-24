import type { Conversation } from "./conversations-provider";

export type ConversationGroup = {
  label: "오늘" | "어제" | "지난 7일" | "지난 30일" | "그 이전";
  items: Conversation[];
};

const LABELS: ConversationGroup["label"][] = ["오늘", "어제", "지난 7일", "지난 30일", "그 이전"];

export function groupByDate(conversations: Conversation[], now: Date): ConversationGroup[] {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const groups = new Map<ConversationGroup["label"], Conversation[]>();
  for (const conversation of conversations) {
    const updated = new Date(conversation.updatedAt);
    const day = new Date(updated.getFullYear(), updated.getMonth(), updated.getDate()).getTime();
    const days = Math.max(0, Math.round((today - day) / 86_400_000));
    const label = days === 0 ? "오늘" : days === 1 ? "어제"
      : days < 7 ? "지난 7일" : days < 30 ? "지난 30일" : "그 이전";
    groups.set(label, [...(groups.get(label) ?? []), conversation]);
  }
  return LABELS.filter((label) => groups.has(label))
    .map((label) => ({ label, items: groups.get(label)! }));
}
