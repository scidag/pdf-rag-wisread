"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Eye, EyeOff, FileText, Loader2 } from "lucide-react";
import { login, register } from "@/lib/auth-store";
import AuthCanvas from "./AuthCanvas";

type Tab = "signin" | "signup";

interface AuthScreenProps {
  initialTab?: Tab;
}

export default function AuthScreen({ initialTab = "signin" }: AuthScreenProps) {
  const router = useRouter();
  const [mounted, setMounted] = useState(false);
  const [tab, setTab] = useState<Tab>(initialTab);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [focus, setFocus] = useState<string | null>(null);
  const firstRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const timer = setTimeout(() => setMounted(true), 80);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    if (mounted) {
      const timer = setTimeout(() => firstRef.current?.focus(), 850);
      return () => clearTimeout(timer);
    }
  }, [mounted, tab]);

  function switchTab(next: Tab) {
    setTab(next);
    setName("");
    setEmail("");
    setPassword("");
    setError(null);
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setLoading(true);
    try {
      if (tab === "signin") {
        await login(email, password);
      } else {
        await register(name, email, password);
      }
      router.push("/workspace");
    } catch (err) {
      setError(err instanceof Error ? err.message : "请求失败");
    } finally {
      setLoading(false);
    }
  }

  const inputStyle = (field: string): React.CSSProperties => ({
    width: "100%",
    background: focus === field ? "rgba(255,255,255,0.055)" : "rgba(255,255,255,0.03)",
    border:
      focus === field
        ? "1px solid rgba(80,140,255,0.55)"
        : "1px solid rgba(255,255,255,0.07)",
    borderRadius: 10,
    padding: "11px 16px",
    color: "rgba(225,235,255,0.9)",
    fontSize: "0.875rem",
    outline: "none",
    boxShadow: focus === field ? "0 0 0 3px rgba(60,120,255,0.1)" : "none",
    transition: "all 0.18s ease"
  });

  return (
    <main className="relative min-h-screen w-screen overflow-x-hidden bg-[#040810]">
      <AuthCanvas />

      <div
        className="absolute right-0 top-0 hidden h-full lg:block"
        style={{
          width: "44%",
          background: "rgba(4,8,18,0.62)",
          backdropFilter: "blur(2px)",
          borderLeft: "1px solid rgba(255,255,255,0.045)"
        }}
      />

      <div className="relative z-10 flex min-h-screen">
        <div className="hidden flex-1 flex-col justify-center pl-16 pr-8 lg:flex">
          <div
            style={{
              opacity: mounted ? 1 : 0,
              transform: mounted ? "translateY(0)" : "translateY(16px)",
              transition:
                "opacity 1s cubic-bezier(0.16,1,0.3,1), transform 1s cubic-bezier(0.16,1,0.3,1)"
            }}
          >
            <div className="mb-14 flex items-center gap-2.5">
              <div
                className="flex items-center justify-center rounded-lg"
                style={{
                  width: 32,
                  height: 32,
                  background: "linear-gradient(145deg, #1e3a6e 0%, #0f2040 100%)",
                  border: "1px solid rgba(80,140,255,0.35)",
                  boxShadow: "0 0 18px rgba(60,120,255,0.2)"
                }}
              >
                <FileText className="h-4 w-4 text-sky-200" />
              </div>
              <span
                style={{
                  color: "rgba(220,235,255,0.92)",
                  fontSize: "1.08rem",
                  fontWeight: 600,
                  letterSpacing: "-0.025em"
                }}
              >
                Wisread
              </span>
            </div>

            <div style={{ maxWidth: 480 }}>
              <h1
                style={{
                  color: "#e8f0ff",
                  fontSize: "clamp(2rem, 3.8vw, 3.2rem)",
                  fontWeight: 700,
                  lineHeight: 1.12,
                  letterSpacing: "-0.04em",
                  margin: 0
                }}
              >
                让 PDF 变成
                <br />
                <span
                  style={{
                    background:
                      "linear-gradient(90deg, #c8dcff 0%, #7eb4ff 55%, #4f8fff 100%)",
                    WebkitBackgroundClip: "text",
                    WebkitTextFillColor: "transparent"
                  }}
                >
                  你的知识库
                </span>
              </h1>
              <p
                style={{
                  color: "rgba(170,200,240,0.55)",
                  fontSize: "0.95rem",
                  lineHeight: 1.65,
                  marginTop: "1.1rem",
                  maxWidth: 380
                }}
              >
                上传 PDF，向文档提问，获得带来源溯源的 AI 回答。
                <br />
                一个工作区，零门槛。
              </p>
            </div>
          </div>
        </div>

        <div className="relative z-10 flex w-full items-center justify-center px-5 py-8 lg:w-[44%] lg:px-10">
          <div
            style={{
              width: "100%",
              maxWidth: 400,
              opacity: mounted ? 1 : 0,
              transform: mounted ? "translateX(0)" : "translateX(20px)",
              transition:
                "opacity 0.9s cubic-bezier(0.16,1,0.3,1), transform 0.9s cubic-bezier(0.16,1,0.3,1)"
            }}
          >
            <div
              style={{
                background: "rgba(6,12,26,0.82)",
                backdropFilter: "blur(24px) saturate(160%)",
                WebkitBackdropFilter: "blur(24px) saturate(160%)",
                border: "1px solid rgba(255,255,255,0.07)",
                borderRadius: 16,
                overflow: "hidden",
                boxShadow:
                  "0 24px 60px rgba(0,0,0,0.6), 0 1px 0 rgba(255,255,255,0.06) inset"
              }}
            >
              <div
                style={{
                  height: 1,
                  background:
                    "linear-gradient(90deg, transparent 0%, rgba(80,140,255,0.5) 40%, rgba(120,180,255,0.4) 70%, transparent 100%)"
                }}
              />

              <div style={{ padding: "28px 28px 32px" }}>
                <div
                  className="flex"
                  style={{
                    background: "rgba(255,255,255,0.035)",
                    borderRadius: 10,
                    padding: 3,
                    marginBottom: 26,
                    border: "1px solid rgba(255,255,255,0.06)"
                  }}
                >
                  {(["signin", "signup"] as Tab[]).map((item) => (
                    <button
                      key={item}
                      type="button"
                      onClick={() => switchTab(item)}
                      style={{
                        flex: 1,
                        padding: "8px 0",
                        borderRadius: 8,
                        fontSize: "0.82rem",
                        fontWeight: 600,
                        letterSpacing: "0.01em",
                        transition: "all 0.2s ease",
                        background:
                          tab === item ? "rgba(255,255,255,0.07)" : "transparent",
                        color:
                          tab === item
                            ? "rgba(220,235,255,0.92)"
                            : "rgba(180,200,230,0.4)",
                        border:
                          tab === item
                            ? "1px solid rgba(255,255,255,0.09)"
                            : "1px solid transparent",
                        boxShadow: tab === item ? "0 1px 4px rgba(0,0,0,0.3)" : "none",
                        cursor: "pointer"
                      }}
                    >
                      {item === "signin" ? "Sign In" : "Sign Up"}
                    </button>
                  ))}
                </div>

                <div style={{ marginBottom: 22 }}>
                  <h2
                    style={{
                      color: "rgba(220,235,255,0.95)",
                      fontSize: "1.15rem",
                      fontWeight: 600,
                      letterSpacing: "-0.025em",
                      lineHeight: 1.3,
                      margin: 0
                    }}
                  >
                    {tab === "signin" ? "Welcome back" : "Create your account"}
                  </h2>
                  <p
                    style={{
                      color: "rgba(160,185,225,0.45)",
                      fontSize: "0.8rem",
                      marginTop: "0.3rem"
                    }}
                  >
                    {tab === "signin"
                      ? "Enter your credentials to continue"
                      : "Start building in seconds"}
                  </p>
                </div>

                <div className="flex gap-2.5" style={{ marginBottom: 20 }}>
                  <SocialButton label="Google" icon={<GoogleIcon />} />
                  <SocialButton label="GitHub" icon={<GithubIcon />} />
                </div>

                <div className="flex items-center gap-3" style={{ marginBottom: 18 }}>
                  <div style={{ flex: 1, height: 1, background: "rgba(255,255,255,0.07)" }} />
                  <span
                    style={{
                      color: "rgba(180,200,230,0.28)",
                      fontSize: "0.7rem",
                      letterSpacing: "0.06em",
                      textTransform: "uppercase"
                    }}
                  >
                    or continue with email
                  </span>
                  <div style={{ flex: 1, height: 1, background: "rgba(255,255,255,0.07)" }} />
                </div>

                <form onSubmit={handleSubmit}>
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    {tab === "signup" && (
                      <input
                        ref={firstRef}
                        type="text"
                        placeholder="Full name"
                        value={name}
                        onChange={(event) => setName(event.target.value)}
                        onFocus={() => setFocus("name")}
                        onBlur={() => setFocus(null)}
                        style={inputStyle("name")}
                        autoComplete="name"
                      />
                    )}

                    <input
                      ref={tab === "signin" ? firstRef : undefined}
                      type="email"
                      placeholder="Email address"
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                      onFocus={() => setFocus("email")}
                      onBlur={() => setFocus(null)}
                      style={inputStyle("email")}
                      autoComplete="email"
                    />

                    <div style={{ position: "relative" }}>
                      <input
                        type={showPassword ? "text" : "password"}
                        placeholder="Password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        onFocus={() => setFocus("password")}
                        onBlur={() => setFocus(null)}
                        style={{ ...inputStyle("password"), paddingRight: 42 }}
                        autoComplete={tab === "signin" ? "current-password" : "new-password"}
                      />
                      <button
                        type="button"
                        tabIndex={-1}
                        onClick={() => setShowPassword((value) => !value)}
                        style={{
                          position: "absolute",
                          right: 12,
                          top: "50%",
                          transform: "translateY(-50%)",
                          color: "rgba(180,200,240,0.35)",
                          background: "none",
                          border: "none",
                          cursor: "pointer",
                          padding: 0,
                          lineHeight: 1
                        }}
                        aria-label="切换密码可见"
                      >
                        {showPassword ? (
                          <EyeOff className="h-4 w-4" />
                        ) : (
                          <Eye className="h-4 w-4" />
                        )}
                      </button>
                    </div>

                    {tab === "signin" && (
                      <div style={{ textAlign: "right", marginTop: -2 }}>
                        <a
                          href="#"
                          style={{
                            color: "rgba(110,165,255,0.72)",
                            fontSize: "0.75rem"
                          }}
                        >
                          Forgot password?
                        </a>
                      </div>
                    )}

                    {error && (
                      <p
                        className="rounded-md px-3 py-2 text-sm"
                        style={{
                          background: "rgba(255,80,90,0.1)",
                          border: "1px solid rgba(255,80,90,0.25)",
                          color: "rgba(255,150,160,0.95)"
                        }}
                      >
                        {error}
                      </p>
                    )}

                    <button
                      type="submit"
                      disabled={loading}
                      style={{
                        width: "100%",
                        marginTop: 4,
                        padding: "12px 0",
                        borderRadius: 10,
                        background: loading
                          ? "rgba(40,90,200,0.45)"
                          : "linear-gradient(135deg, #2860d6 0%, #3a7fff 100%)",
                        color: loading ? "rgba(255,255,255,0.5)" : "rgba(230,242,255,0.96)",
                        fontSize: "0.875rem",
                        fontWeight: 600,
                        letterSpacing: "-0.01em",
                        border: "1px solid rgba(80,150,255,0.35)",
                        boxShadow: loading
                          ? "none"
                          : "0 4px 20px rgba(40,100,255,0.28), 0 1px 0 rgba(255,255,255,0.1) inset",
                        cursor: loading ? "not-allowed" : "pointer",
                        transform: loading ? "scale(0.99)" : "scale(1)",
                        transition: "all 0.18s ease",
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        gap: 8
                      }}
                    >
                      {loading && <Loader2 className="h-4 w-4 animate-spin" />}
                      {tab === "signin" ? "Continue" : "Create account"}
                      {!loading && <ArrowRight className="h-3.5 w-3.5" />}
                    </button>
                  </div>
                </form>

                <p
                  style={{
                    textAlign: "center",
                    marginTop: 20,
                    color: "rgba(160,185,225,0.32)",
                    fontSize: "0.76rem"
                  }}
                >
                  {tab === "signin" ? (
                    <>
                      No account?{" "}
                      <button
                        type="button"
                        onClick={() => switchTab("signup")}
                        style={{
                          color: "rgba(110,165,255,0.7)",
                          background: "none",
                          border: "none",
                          cursor: "pointer",
                          fontSize: "inherit"
                        }}
                      >
                        Sign up free →
                      </button>
                    </>
                  ) : (
                    <>
                      Already have an account?{" "}
                      <button
                        type="button"
                        onClick={() => switchTab("signin")}
                        style={{
                          color: "rgba(110,165,255,0.7)",
                          background: "none",
                          border: "none",
                          cursor: "pointer",
                          fontSize: "inherit"
                        }}
                      >
                        Sign in
                      </button>
                    </>
                  )}
                </p>
              </div>
            </div>

            <p
              style={{
                textAlign: "center",
                marginTop: 14,
                color: "rgba(140,170,215,0.22)",
                fontSize: "0.67rem",
                lineHeight: 1.6
              }}
            >
              By continuing, you agree to our{" "}
              <a href="#" style={{ color: "rgba(140,170,215,0.45)" }}>
                Terms of Service
              </a>{" "}
              and{" "}
              <a href="#" style={{ color: "rgba(140,170,215,0.45)" }}>
                Privacy Policy
              </a>
              .
            </p>
          </div>
        </div>
      </div>
    </main>
  );
}

function SocialButton({ label, icon }: { label: string; icon: React.ReactNode }) {
  return (
    <button
      type="button"
      disabled
      title="即将支持"
      style={{
        flex: 1,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 7,
        padding: "10px 0",
        borderRadius: 10,
        background: "rgba(255,255,255,0.03)",
        border: "1px solid rgba(255,255,255,0.065)",
        color: "rgba(200,220,250,0.4)",
        fontSize: "0.82rem",
        fontWeight: 500,
        cursor: "not-allowed",
        opacity: 0.75
      }}
    >
      {icon}
      {label}
    </button>
  );
}

function GoogleIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24">
      <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4" />
      <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853" />
      <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05" />
      <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335" />
    </svg>
  );
}

function GithubIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="rgba(200,220,255,0.7)">
      <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
    </svg>
  );
}
