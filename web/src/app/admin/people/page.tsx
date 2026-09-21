import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PeopleAdminPanel } from "./people-admin-panel";
import { callControlPlane } from "@/lib/control-plane";
import type { Person } from "@/lib/people";

export default async function PeopleAdminPage() {
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");

  const result = await callControlPlane<Person[]>("/api/v1/admin/people");
  if (!result.ok) {
    if (result.status === 403) redirect("/");
    return <p className="text-sm">{result.message}</p>;
  }

  return <PeopleAdminPanel initialPeople={result.data} />;
}
