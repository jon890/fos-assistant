import type { Conversation } from "./conversations-provider";

export type TaskGroup = {
  taskId: string;
  title: string;
  conversations: Conversation[];
};

function byRecent(a: Conversation, b: Conversation): number {
  return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
}

/**
 * 작업이 만든 대화를 작업마다 모으고 나머지는 따로 둔다.
 *
 * <p>작업은 가장 최근 대화가 바뀐 순이고 작업 안의 대화도 최근 순이다.
 */
export function groupByTask(conversations: Conversation[]): {
  tasks: TaskGroup[];
  others: Conversation[];
} {
  const groups = new Map<string, TaskGroup>();
  const others: Conversation[] = [];
  for (const conversation of conversations) {
    if (conversation.taskId === null || conversation.taskId === undefined) {
      others.push(conversation);
      continue;
    }
    const group = groups.get(conversation.taskId);
    if (group) group.conversations.push(conversation);
    else
      groups.set(conversation.taskId, {
        taskId: conversation.taskId,
        title: conversation.taskTitle ?? "",
        conversations: [conversation],
      });
  }
  const tasks = [...groups.values()];
  for (const group of tasks) group.conversations.sort(byRecent);
  tasks.sort((a, b) => byRecent(a.conversations[0], b.conversations[0]));
  return { tasks, others };
}
