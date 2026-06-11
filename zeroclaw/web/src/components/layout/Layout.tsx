import { useState, useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import Sidebar from '@/components/layout/Sidebar';
import Header from '@/components/layout/Header';
import ReloadBanner from '@/components/layout/ReloadBanner';
import UnsavedChangesBanner from '@/components/layout/UnsavedChangesBanner';
import { ErrorBoundary } from '@/App';

const SIDEBAR_COLLAPSED_KEY = 'zeroclaw-sidebar-collapsed';

export default function Layout() {
  const { pathname } = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === 'true';
    } catch {
      return false;
    }
  });

  // Close sidebar on route change (mobile navigation)
  useEffect(() => {
    setSidebarOpen(false);
  }, [pathname]);

  // Persist collapsed state
  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(collapsed));
    } catch {
      // localStorage may not be available
    }
  }, [collapsed]);

  return (
    <div className="min-h-screen text-white" style={{ background: 'var(--pc-bg-base)' }}>
      {/* Fixed sidebar */}
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} collapsed={collapsed} />

      {/* Main area — offset by sidebar width on desktop, full-width on mobile */}
      <div
        className={`
          flex flex-col flex-1 min-w-0 h-screen transition-all duration-300 ease-in-out
          ${collapsed ? 'md:ml-14' : 'md:ml-60'}
          ml-0
        `}
      >
        <Header
          onMenuToggle={() => setSidebarOpen((v) => !v)}
          onCollapseToggle={() => setCollapsed((c) => !c)}
          collapsed={collapsed}
        />
        <ReloadBanner />
        <UnsavedChangesBanner />

        {/* Page content — wallet-card glass container over aurora backdrop */}
        <main className="flex-1 overflow-y-auto min-h-0 p-4 md:p-6 bg-aurora overflow-x-hidden">
          <div className="hologram-card rounded-2xl p-5 md:p-6 min-h-full">
            <ErrorBoundary key={pathname.split('/')[1] ?? ''}>
              <Outlet />
            </ErrorBoundary>
          </div>
        </main>
      </div>
    </div>
  );
}
