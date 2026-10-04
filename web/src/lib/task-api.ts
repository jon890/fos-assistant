/**
 * 예약 작업의 서버 라우트를 부른다. 응답을 읽고 실패를 문구로 바꾸는 일은 부르는 쪽이 한다.
 */

const JSON_HEADERS = { "Content-Type": "application/json" };

function taskPath(id: string): string {
  return `/api/tasks/${id}`;
}

export function fetchTasks(): Promise<Response> {
  return fetch("/api/tasks", { cache: "no-store" });
}

export function fetchTask(id: string): Promise<Response> {
  return fetch(taskPath(id), { cache: "no-store" });
}

export function createTask(body: unknown): Promise<Response> {
  return fetch("/api/tasks", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
}

export function updateTask(id: string, body: unknown): Promise<Response> {
  return fetch(taskPath(id), {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  });
}

export function pauseTask(id: string): Promise<Response> {
  return fetch(`${taskPath(id)}/pause`, { method: "POST" });
}

export function resumeTask(id: string): Promise<Response> {
  return fetch(`${taskPath(id)}/resume`, { method: "POST" });
}

export function deleteTask(id: string): Promise<Response> {
  return fetch(taskPath(id), { method: "DELETE" });
}

/** 최근 실행을 예정 시각의 역순으로 읽는다. */
export function fetchTaskRuns(id: string, limit: number): Promise<Response> {
  return fetch(`${taskPath(id)}/runs?limit=${limit}`, { cache: "no-store" });
}
