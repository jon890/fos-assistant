import { signIn } from "@/auth";
import { Button } from "@/components/ui/button";

export default function SignInPage() {
  return (
    <section className="flex flex-col items-start gap-4 py-16">
      <h1 className="text-xl font-semibold">로그인</h1>
      <p className="text-muted">가족 구성원으로 등록된 계정만 들어올 수 있다.</p>
      <form
        action={async () => {
          "use server";
          await signIn("google", { redirectTo: "/" });
        }}
      >
        <Button type="submit" variant="secondary">
          Google 계정으로 로그인
        </Button>
      </form>
    </section>
  );
}
