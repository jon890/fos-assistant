import NextAuth from "next-auth";
import Google from "next-auth/providers/google";

/** Only these addresses may sign in. Anyone else is rejected before a token is ever minted. */
function allowedEmails(): string[] {
  return (process.env.ASSISTANT_ALLOWED_EMAILS ?? "")
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry.length > 0);
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [Google],
  callbacks: {
    signIn({ profile }) {
      const email = profile?.email?.toLowerCase();
      if (!email) return false;
      const allowed = allowedEmails();
      return allowed.length > 0 && allowed.includes(email);
    },
  },
  pages: { signIn: "/signin" },
});
