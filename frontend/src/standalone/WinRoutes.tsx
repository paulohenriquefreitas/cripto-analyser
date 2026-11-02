import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import { WinMt5Page } from '../features/win/pages/WinMt5Page';
import { WinScannerPage } from '../features/win/pages/WinScannerPage';
import { ReplayPage } from '../features/replay/pages/ReplayPage';
import { SettingsPage } from '../features/settings/pages/SettingsPage';
import { StandaloneLayout } from './StandaloneLayout';

const router = createBrowserRouter([{ element: <StandaloneLayout title="WIN M5 / Fluxo" links={[
  { to: '/win-m5', label: 'WIN M5' }, { to: '/win-scanner', label: 'WIN Scanner' },
  { to: '/replay', label: 'Replay e pesquisa' }, { to: '/settings', label: 'Configurações' },
]} />, children: [
  { path: '/', element: <Navigate to="/win-m5" replace /> },
  { path: '/win-m5', element: <WinMt5Page /> },
  { path: '/win-scanner', element: <WinScannerPage /> },
  { path: '/replay', element: <ReplayPage /> },
  { path: '/settings', element: <SettingsPage /> },
] }]);
export function WinRoutes() { return <RouterProvider router={router} />; }
