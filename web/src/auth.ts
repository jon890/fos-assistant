import NextAuth from "next-auth";
import Google from "next-auth/providers/google";

/**
 * Fails on the first request rather than serving a sign-in page that cannot work.
 *
 * <p>These live here instead of in the compose file because the backend can be deployed and
 * verified before the Google client exists, and a required-variable check in compose would block
 * that whole file from loading.
 */
function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value || value.trim().length === 0) {
    throw new Error(`${name} is not set; the web tier cannot sign anyone in without it`);
  }
  return value;
}

/** Only these addresses may sign in. Anyone else is rejected before a token is ever minted. */
function allowedEmails(): string[] {
  return (process.env.ASSISTANT_ALLOWED_EMAILS ?? "")
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry.length > 0);
}

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    Google({
      clientId: requireEnv("AUTH_GOOGLE_ID"),
      clientSecret: requireEnv("AUTH_GOOGLE_SECRET"),
    }),
  ],
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
