import {
  Component,
  createContext,
  useContext,
  useEffect,
  useState,
  type ErrorInfo,
  type ReactNode,
} from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ThemeProvider } from "./contexts/ThemeContext";

import { loadLocale, saveLocale } from "./contexts/ThemeContext";
import { AuthProvider, useAuth } from "./hooks/useAuth";
import { DraftContext, useDraftStore } from "./hooks/useDraft";
import { getAdminPairCode, getQuickstartState } from "./lib/api";
import { ConfigDraftProvider } from "./lib/draftStore";
import { setLocale, type Locale } from "./lib/i18n";
import { Router } from "./router/router";

// Locale context
interface LocaleContextType {
  locale: string;
  setAppLocale: (locale: string) => void;
}

export const LocaleContext = createContext<LocaleContextType>({
  locale: "en",
  setAppLocale: () => {},
});

export const useLocaleContext = () => useContext(LocaleContext);

// ---------------------------------------------------------------------------
// Error boundary — catches render crashes and shows a recoverable message
// instead of a black screen
// ---------------------------------------------------------------------------

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends Component<
  { children: ReactNode },
  ErrorBoundaryState
> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[ZeroClaw] Render error:", error, info.componentStack);
    // Stale-chunk recovery: when Vite rebuilds, the loaded index.html
    // still references the previous chunk hashes. A dynamic import for
    // a lazy route then 404s with "error loading dynamically imported
    // module". Reload once so the user gets the new index.html and the
    // current chunk hashes; the sessionStorage marker prevents reload
    // loops if reload doesn't actually help.
    if (
      isChunkLoadError(error) &&
      !sessionStorage.getItem("zeroclaw-chunk-reloaded")
    ) {
      sessionStorage.setItem("zeroclaw-chunk-reloaded", "1");
      window.location.reload();
    }
  }

  render() {
    if (this.state.error) {
      return (
        <div className="p-6">
          <div
            className="card p-6 w-full max-w-lg"
            style={{ borderColor: "var(--color-status-error-alpha-30)" }}
          >
            <h2
              className="text-lg font-semibold mb-2"
              style={{ color: "var(--color-status-error)" }}
            >
              Something went wrong
            </h2>
            <p
              className="text-sm mb-4"
              style={{ color: "var(--pc-text-muted)" }}
            >
              A render error occurred. Check the browser console for details.
            </p>
            <pre
              className="text-xs rounded-lg p-3 overflow-x-auto whitespace-pre-wrap break-all font-mono"
              style={{
                background: "var(--pc-bg-base)",
                color: "var(--color-status-error)",
              }}
            >
              {this.state.error.message}
            </pre>
            <button
              onClick={() => {
                sessionStorage.removeItem("zeroclaw-chunk-reloaded");
                this.setState({ error: null });
              }}
              className="btn-electric mt-6 px-4 py-2 text-sm font-medium"
            >
              Try again
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

function isChunkLoadError(error: Error): boolean {
  const m = error?.message ?? "";
  return (
    m.includes("dynamically imported module") ||
    m.includes("Failed to fetch dynamically") ||
    m.includes("Importing a module script failed") ||
    error?.name === "ChunkLoadError"
  );
}

// Pairing dialog component
function PairingDialog({
  onPair,
}: {
  onPair: (code: string) => Promise<void>;
}) {
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [displayCode, setDisplayCode] = useState<string | null>(null);
  const [codeLoading, setCodeLoading] = useState(true);

  // Fetch the current pairing code (public endpoint works in Docker too)
  useEffect(() => {
    let cancelled = false;
    getAdminPairCode()
      .then((data) => {
        if (!cancelled && data.pairing_code) {
          setDisplayCode(data.pairing_code);
          setCode(data.pairing_code); // auto-fill so user just clicks "Pair"
        }
      })
      .catch(() => {
        // Endpoint not reachable — user must check terminal / docker logs
      })
      .finally(() => {
        if (!cancelled) setCodeLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError("");
    try {
      await onPair(code);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Pairing failed");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="min-h-screen flex items-center justify-center relative overflow-hidden"
      style={{ background: "var(--pc-bg-base)" }}
    >
      <div className="relative w-full max-w-sm animate-fade-in-scale" style={{ zIndex: 1 }}>
        <div className="rounded-xl border p-8 text-center"
          style={{
            background: "var(--pc-bg-surface)",
            borderColor: "var(--pc-border)",
          }}>
          <div className="mb-6">
            <div
              className="w-16 h-16 mx-auto mb-5 rounded-2xl flex items-center justify-center"
              style={{
                background: "var(--pc-accent-glow)",
                border: "1px solid var(--pc-accent-dim)",
              }}
            >
              <svg width="28" height="28" viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M14 2L17.09 8.26L24 9.27L19 14.14L20.18 21.02L14 17.77L7.82 21.02L9 14.14L4 9.27L10.91 8.26L14 2Z" fill="var(--pc-accent)" opacity="0.9"/>
              </svg>
            </div>
            <h1 className="text-xl font-semibold mb-1" style={{ color: "var(--pc-text-primary)", letterSpacing: "-0.02em" }}>
              SimonAI
            </h1>
            <p className="text-sm" style={{ color: "var(--pc-text-muted)" }}>
              {displayCode
                ? "Your pairing code — ready to connect"
                : "Enter the 6-digit code from your terminal"}
            </p>
          </div>

          {/* Show the pairing code if available (localhost) */}
          {!codeLoading && displayCode && (
            <div
              className="mb-6 p-5 rounded-xl text-center"
              style={{
                background: "var(--pc-accent-glow)",
                border: "1px solid var(--pc-accent-dim)",
              }}
            >
              <div
                className="text-3xl font-mono font-bold tracking-[0.4em] py-2"
                style={{ color: "var(--pc-text-primary)" }}
              >
                {displayCode}
              </div>
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="6-digit code"
              className="w-full px-4 py-3.5 text-center text-2xl tracking-[0.3em] font-medium mb-4 rounded-lg"
              style={{
                background: "var(--pc-bg-input)",
                border: "1px solid var(--pc-border)",
                color: "var(--pc-text-primary)",
                fontFamily: "var(--pc-font-mono)",
              }}
              onFocus={(e) => {
                e.target.style.borderColor = "var(--pc-accent-dim)";
                e.target.style.boxShadow = "0 0 0 3px var(--pc-accent-glow)";
              }}
              onBlur={(e) => {
                e.target.style.borderColor = "var(--pc-border)";
                e.target.style.boxShadow = "none";
              }}
              maxLength={6}
              autoFocus
            />
            {error && (
              <p
                aria-live="polite"
                className="text-sm mb-4 text-center animate-fade-in"
                style={{ color: "var(--color-status-error)" }}
              >
                {error}
              </p>
            )}
            <button
              type="submit"
              disabled={loading || code.length < 6}
              className="w-full py-3 text-sm font-semibold rounded-lg transition-all duration-200"
              style={{
                background: loading || code.length < 6 ? "var(--pc-bg-elevated)" : "var(--pc-accent)",
                color: loading || code.length < 6 ? "var(--pc-text-muted)" : "#fff",
                border: loading || code.length < 6 ? "1px solid var(--pc-border)" : "1px solid transparent",
                cursor: loading || code.length < 6 ? "not-allowed" : "pointer",
              }}
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span className="h-4 w-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  Pairing...
                </span>
              ) : (
                "Pair"
              )}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

function AppContent() {
  const { isAuthenticated, requiresPairing, loading, pair, logout } = useAuth();
  const [locale, setLocaleState] = useState(loadLocale());
  const draftStore = useDraftStore();
  setLocale(locale as Locale);

  const setAppLocale = (newLocale: string) => {
    setLocaleState(newLocale);
    setLocale(newLocale as Locale);
    saveLocale(newLocale);
  };

  // Listen for 401 events to force logout
  useEffect(() => {
    window.addEventListener("zeroclaw-unauthorized", logout);
    return () => window.removeEventListener("zeroclaw-unauthorized", logout);
  }, [logout]);

  if (loading) {
    return (
      <div
        className="min-h-screen flex items-center justify-center"
        style={{ background: "var(--pc-bg-base)" }}
      >
        <div className="flex flex-col items-center gap-4 animate-fade-in">
          <div
            className="h-10 w-10 border-2 rounded-full animate-spin"
            style={{
              borderColor: "var(--pc-border)",
              borderTopColor: "var(--pc-accent)",
            }}
          />
          <p className="text-sm" style={{ color: "var(--pc-text-muted)" }}>
            Connecting...
          </p>
        </div>
      </div>
    );
  }

  if (!isAuthenticated && requiresPairing) {
    return <PairingDialog onPair={pair} />;
  }

  return (
    <DraftContext.Provider value={draftStore}>
      <ConfigDraftProvider>
        <LocaleContext.Provider value={{ locale, setAppLocale }}>
          <FreshInstallRedirect />
          <Router />
        </LocaleContext.Provider>
      </ConfigDraftProvider>
    </DraftContext.Provider>
  );
}

// Redirects fresh installs (no agents yet, Quickstart never completed)
// from `/` to `/quickstart`. The daemon always writes a default
// config.toml on init, so file existence isn't the right signal —
// we ask the gateway via /api/quickstart/state which reports
// quickstart_completed plus the live agents list.
//
// Fires once per session. Only redirects when the user lands at `/` —
// manual navigation to other routes is left alone, so returning users
// who already have agents can always reach Quickstart from the nav.
function FreshInstallRedirect() {
  const navigate = useNavigate();
  const location = useLocation();
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (checked) return;
    setChecked(true);
    if (location.pathname !== "/") return;
    void getQuickstartState()
      .then((state) => {
        if (!state.quickstart_completed && state.agents.length === 0) {
          navigate("/quickstart", { replace: true });
        }
      })
      .catch(() => {
        // Status check failed (network blip, gateway hiccup); the
        // dashboard renders normally as the safe default.
      });
  }, [checked, location.pathname, navigate]);

  return null;
}

export default function App() {
  return (
    <AuthProvider>
      <ThemeProvider>
        <AppContent />
      </ThemeProvider>
    </AuthProvider>
  );
}
