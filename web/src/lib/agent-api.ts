/** 에이전트 화면이 부르는 요청이다. 응답을 읽고 오류를 보이는 일은 화면이 맡는다. */

const JSON_HEADERS = { "Content-Type": "application/json" };

function toolsPath(code: string, admin: boolean): string {
  return admin
    ? `/api/admin/agents/${code}/tools`
    : `/api/agents/${code}/tools`;
}

export function fetchAdminAgents(): Promise<Response> {
  return fetch("/api/admin/agents");
}

export function createAdminAgent(
  input: Record<string, unknown>,
): Promise<Response> {
  return fetch("/api/admin/agents", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}

export function updateAdminAgent(
  code: string,
  input: Record<string, unknown>,
): Promise<Response> {
  return fetch(`/api/admin/agents/${code}`, {
    method: "PATCH",
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}

export function createAgent(input: {
  name: string;
  visibility: string;
}): Promise<Response> {
  return fetch("/api/agents", {
    method: "POST",
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}

export function deleteAgent(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}`, { method: "DELETE" });
}

export function changeAgentVisibility(
  code: string,
  visibility: string,
): Promise<Response> {
  return fetch(`/api/agents/${code}/visibility`, {
    method: "PATCH",
    headers: JSON_HEADERS,
    body: JSON.stringify({ visibility }),
  });
}

export function fetchPersona(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/persona`, { cache: "no-store" });
}

export function savePersona(
  code: string,
  input: { body: string; baseHash: string },
): Promise<Response> {
  return fetch(`/api/agents/${code}/persona`, {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}

export function fetchAgentTools(
  code: string,
  admin: boolean,
): Promise<Response> {
  return fetch(toolsPath(code, admin), { cache: "no-store" });
}

export function saveAgentTools(
  code: string,
  admin: boolean,
  enabled: string[],
): Promise<Response> {
  return fetch(toolsPath(code, admin), {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify({ enabled }),
  });
}

export function fetchAgentSkills(code: string): Promise<Response> {
  return fetch(`/api/agents/${code}/skills`, { cache: "no-store" });
}

export function setSkillEnabled(
  code: string,
  name: string,
  enabled: boolean,
): Promise<Response> {
  return fetch(`/api/agents/${code}/skills/${name}/enabled`, {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify({ enabled }),
  });
}

export function deleteSkill(code: string, name: string): Promise<Response> {
  return fetch(`/api/agents/${code}/skills/${name}`, { method: "DELETE" });
}

export function saveSkill(
  code: string,
  name: string,
  input: unknown,
): Promise<Response> {
  return fetch(`/api/agents/${code}/skills/${name}`, {
    method: "PUT",
    headers: JSON_HEADERS,
    body: JSON.stringify(input),
  });
}
