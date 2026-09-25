import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { describeError } from "@/components/error-message";
import { PersonaEditor } from "@/components/agent/persona-editor";
import { StarterEditor } from "@/components/agent/starter-editor";
import { callControlPlane } from "@/lib/control-plane";
import type { AgentView, PersonaView, StartersView } from "@/lib/agent";

export default async function AgentPersonaPage({
  params,
}: {
  params: Promise<{ code: string }>;
}) {
  const { code } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const [agentsResult, personaResult, startersResult] = await Promise.all([
    callControlPlane<AgentView[]>("/api/v1/agents"),
    callControlPlane<PersonaView>(`/api/v1/agents/${code}/persona`),
    callControlPlane<StartersView>(`/api/v1/agents/${code}/starters`),
  ]);
  const name = agentsResult.ok
    ? (agentsResult.data.find((agent) => agent.code === code)?.name ?? code)
    : code;

  if (!personaResult.ok) {
    return (
      <div className="mx-auto w-full max-w-2xl">
        <h1 className="mb-4 text-xl font-semibold">{name}</h1>
        <p role="alert" className="rounded-md border border-border bg-surface p-3 text-sm">
          {describeError(personaResult.code, personaResult.message)}
        </p>
      </div>
    );
  }

  return (
    <>
      <PersonaEditor code={code} name={name} initialPersona={personaResult.data} />
      {startersResult.ok ? (
        <StarterEditor code={code} name={name} initialStarters={startersResult.data} />
      ) : (
        <div className="mx-auto mt-8 w-full max-w-2xl">
          <p role="alert" className="rounded-md border border-border bg-surface p-3 text-sm">
            {describeError(startersResult.code, startersResult.message)}
          </p>
        </div>
      )}
    </>
  );
}
