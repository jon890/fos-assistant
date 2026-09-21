"use client";

import { useState } from "react";
import { PersonForm } from "@/components/admin/person-form";
import { PersonList } from "@/components/admin/person-list";
import { describeError } from "@/components/error-message";
import type { Person } from "@/lib/people";

type Props = { initialPeople: Person[] };

type Failure = { code: string; message: string };

async function payload<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function PeopleAdminPanel({ initialPeople }: Props) {
  const [people, setPeople] = useState(initialPeople);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function reload() {
    const response = await fetch("/api/admin/people");
    if (!response.ok) throw await payload<Failure>(response);
    setPeople(await payload<Person[]>(response));
  }

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setBusy(true);
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
        setError(describeError(result.code, result.message));
      }
    } finally {
      setBusy(false);
    }
  }

  async function changeEnabled(person: Person) {
    setBusy(true);
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
        setError(describeError(result.code, result.message));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto w-full max-w-4xl">
      <h1 className="mb-2 text-xl font-semibold">사람 관리</h1>
      <p className="mb-6 max-w-2xl text-sm leading-6 text-muted">
        여기서 더하면 그 사람의 Hermes profile 까지 만들어진다. 사용자와 에이전트는 그 사람이 처음
        로그인할 때 생긴다.
      </p>
      <PersonForm busy={busy} onCreate={(event) => void create(event)} />
      {error ? (
        <p role="alert" data-testid="people-error" className="mb-4 rounded-md bg-surface p-3 text-sm">
          {error}
        </p>
      ) : null}
      <PersonList
        people={people}
        busy={busy}
        onEnabledChange={(person) => void changeEnabled(person)}
      />
    </div>
  );
}
