import { Navigate, RouterProvider, createBrowserRouter } from 'react-router-dom';

import { AppLayout } from '@/common/layout/AppLayout';
import { DashboardPage } from '@/features/dashboard/pages/DashboardPage';
import { ScannerPage } from '@/features/scanner/pages/ScannerPage';
import { SettingsPage } from '@/features/settings/pages/SettingsPage';
import { StockDetailPage } from '@/features/stock/pages/StockDetailPage';
import { WinScannerPage } from '@/features/win/pages/WinScannerPage';

import { WinMt5Page } from '@/features/win/pages/WinMt5Page';

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'scanner', element: <ScannerPage /> },
      { path: 'win-m5', element: <WinMt5Page /> },
      { path: 'win-scanner', element: <WinScannerPage /> },
      { path: 'stock/:ticker', element: <StockDetailPage /> },
      { path: 'settings', element: <SettingsPage /> },
    ],
  },
]);

export function AppRouter() {
  return <RouterProvider router={router} />;
}
