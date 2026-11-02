import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';
import { ScannerPage } from '../features/scanner/pages/ScannerPage';
import { ExperimentalScannerPage } from '../features/scanner/pages/ExperimentalScannerPage';
import { StockDetailPage } from '../features/stock/pages/StockDetailPage';
import { SettingsPage } from '../features/settings/pages/SettingsPage';
import { StandaloneLayout } from './StandaloneLayout';

const router = createBrowserRouter([{ element: <StandaloneLayout title="Scanner" links={[
  { to: '/scanner', label: 'Scanner' }, { to: '/scanner-experimental', label: 'Pesquisa experimental' },
  { to: '/stock/PETR4', label: 'Detalhe do ativo' }, { to: '/settings', label: 'Configurações' },
]} />, children: [
  { path: '/', element: <Navigate to="/scanner" replace /> },
  { path: '/dashboard', element: <Navigate to="/scanner" replace /> },
  { path: '/scanner', element: <ScannerPage /> },
  { path: '/scanner-experimental', element: <ExperimentalScannerPage /> },
  { path: '/stock/:ticker', element: <StockDetailPage /> },
  { path: '/settings', element: <SettingsPage /> },
] }]);
export function ScannerRoutes() { return <RouterProvider router={router} />; }
