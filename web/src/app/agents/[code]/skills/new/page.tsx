import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { SkillEditor } from "@/components/agent/skill-editor";
import { AGENT_CODE_PATTERN } from "@/lib/agent";

/** 서버에서 Control Plane 을 읽지 않는다. 이름이 겹치는지는 편집기가 저장하기 전에 브라우저에서 확인한다. */
export default async function NewSkillPage({
  params,
}: {
  params: Promise<{ code: string }>;
}) {
  const { code } = await params;
  const session = await auth();
  if (!session?.user?.email) redirect("/signin");
  if (!AGENT_CODE_PATTERN.test(code)) notFound();

  return <SkillEditor code={code} initial={null} />;
}
