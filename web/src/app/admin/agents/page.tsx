import { redirect } from "next/navigation";

export default async function AgentAdminPage() {
  redirect("/agents");
}
