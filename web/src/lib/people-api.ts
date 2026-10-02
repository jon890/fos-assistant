/** 관리자 사용자 관리 화면이 부르는 요청이다. 응답을 읽고 오류를 보이는 일은 화면이 맡는다. */

export function fetchPeople(): Promise<Response> {
  return fetch("/api/admin/people");
}

export function createPerson(input: {
  email: FormDataEntryValue | null;
  displayName: FormDataEntryValue | null;
  hermesProfile: FormDataEntryValue | null;
}): Promise<Response> {
  return fetch("/api/admin/people", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function setPersonEnabled(
  id: number,
  enabled: boolean,
): Promise<Response> {
  return fetch(`/api/admin/people/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
}
