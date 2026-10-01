"use client";

import { useState } from "react";
import { PersonForm } from "@/components/admin/person-form";
import { PersonList } from "@/components/admin/person-list";
import { describeAdminError } from "@/components/error-message";
import { Notice } from "@/components/ui/notice";
import type { Person } from "@/lib/people";

type Props = { initialPeople: Person[] };

type Failure = { code: string; message: string };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function PeopleAdminPanel({ initialPeople }: Props) {
  const [people, setPeople] = useState(initialPeople);
  const [error, setError] = useState<string | null>(null);
  /** 도는 요청이다. 누른 단추에만 회전 표시를 두려고 더하기인지 누구를 켜고 끄는지 기억한다. */
  const [pending, setPending] = useState<
    { action: "create" } | { action: "enabled"; id: Person["id"] } | null
  >(null);
  const busy = pending !== null;

  async function reload() {
    const response = await fetch("/api/admin/people");
    if (!response.ok) throw await payload<Failure>(response);
    setPeople(await payload<Person[]>(response));
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setPending({ action: "create" });
    setError(null);
    try {
      const form = new FormData(formElement);
      const response = await fetch("/api/admin/people", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          email: form.get("email"),
          displayName: form.get("displayName"),
          hermesProfile: form.get("hermesProfile"),
        }),
      });
      if (response.ok) {
        formElement.reset();
        await reload();
      } else {
        const result = await payload<Failure>(response);
        setError(describeAdminError(result.code, result.message));
      }
    } finally {
      setPending(null);
    }
  }

  async function changeEnabled(person: Person) {
    setPending({ action: "enabled", id: person.id });
    setError(null);
    try {
      const response = await fetch(`/api/admin/people/${person.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ enabled: !person.enabled }),
      });
      if (response.ok) await reload();
      else {
        const result = await payload<Failure>(response);
        setError(describeAdminError(result.code, result.message));
      }
    } finally {
      setPending(null);
    }
  }

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">사용자 관리</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted-foreground">
        여기서 사용자를 추가하면 기본 profile이 만들어져요. 에이전트는 그
        사용자가 처음 로그인할 때 만들어져요.
      </p>
      <PersonForm
        busy={busy}
        creating={pending?.action === "create"}
        onCreate={(event) => void create(event)}
      />
      {error ? (
        <Notice
          variant="error"
          role="alert"
          data-testid="people-error"
          className="mb-4"
        >
          {error}
        </Notice>
      ) : null}
      <PersonList
        people={people}
        busy={busy}
        pendingId={pending?.action === "enabled" ? pending.id : null}
        onEnabledChange={(person) => void changeEnabled(person)}
      />
    </div>
  );
}
