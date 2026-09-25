import { signIn } from "@/auth";
import { Button } from "@/components/ui/button";

export default function SignInPage() {
  return (
    <section className="grid min-h-full place-items-center">
      <div className="flex w-full max-w-sm flex-col items-center rounded-md border border-border bg-surface p-6 text-center shadow-sm sm:p-8">
        <span
          aria-hidden="true"
          className="flex h-12 w-12 items-center justify-center rounded-full bg-brand text-lg font-semibold text-on-brand"
        >
          우
        </span>
        <h1 className="mt-4 text-2xl font-semibold">우리집 비서</h1>
        <p className="mt-2 text-sm leading-6 text-muted">
          사용자로 등록된 Google 계정으로 로그인한다.
        </p>
        <form
          className="mt-6 w-full"
          action={async () => {
            "use server";
            await signIn("google", { redirectTo: "/" });
          }}
        >
          <Button type="submit" variant="primary" className="w-full">
            Google 계정으로 로그인
          </Button>
        </form>
      </div>
    </section>
  );
}
