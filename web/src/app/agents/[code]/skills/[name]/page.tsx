import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { describeError } from "@/components/error-message";
import { SkillEditor } from "@/components/agent/skill-editor";
import { Button } from "@/components/ui/button";
import { AGENT_CODE_PATTERN } from "@/lib/agent";
import { callControlPlane } from "@/lib/control-plane";
import { SKILL_NAME_PATTERN, type SkillDetailView } from "@/lib/skill";

export default async function EditSkillPage({
  params,
}: {
  params: Promise<{ code: string; name: string }>;
}) {
  const { code, name } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  if (!AGENT_CODE_PATTERN.test(code) || !SKILL_NAME_PATTERN.test(name)) notFound();

  const result = await callControlPlane<SkillDetailView>(`/api/v1/agents/${code}/skills/${name}`);
  if (!result.ok) {
    const message = result.code === "FORBIDDEN"
      ? "이 스킬은 에이전트의 주인과 관리자만 고칠 수 있어요."
      : describeError(result.code, result.message);
    return (
      <div className="mx-auto w-full max-w-3xl">
        <h1 className="mb-4 text-xl font-semibold">{name} 스킬</h1>
        <p role="alert" className="rounded-md border border-border bg-muted p-3 text-sm">{message}</p>
        <Button asChild variant="outline" className="mt-4">
          <Link href={`/agents/${code}`}>에이전트로 돌아가기</Link>
        </Button>
      </div>
    );
  }

  return <SkillEditor code={code} initial={result.data} />;
}
