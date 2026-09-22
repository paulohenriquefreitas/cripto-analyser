import DashboardRoundedIcon from '@mui/icons-material/DashboardRounded';
import InsightsRoundedIcon from '@mui/icons-material/InsightsRounded';
import SettingsRoundedIcon from '@mui/icons-material/SettingsRounded';
import ShowChartRoundedIcon from '@mui/icons-material/ShowChartRounded';
import SsidChartRoundedIcon from '@mui/icons-material/SsidChartRounded';
import {
  AppBar,
  Box,
  Button,
  Container,
  Drawer,
  Stack,
  Toolbar,
  Typography,
} from '@mui/material';
import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

const drawerWidth = 248;

export function AppLayout() {
  const { t } = useTranslation();
  const items = [
    { to: '/dashboard', label: t('navigation.dashboard'), icon: <DashboardRoundedIcon /> },
    { to: '/scanner', label: t('navigation.scanner'), icon: <InsightsRoundedIcon /> },
    { to: '/win-m5', label: 'WIN M5', icon: <ShowChartRoundedIcon /> },
    { to: '/win-scanner', label: t('navigation.winScanner'), icon: <SsidChartRoundedIcon /> },
    { to: '/stock/PETR4', label: t('navigation.stock'), icon: <ShowChartRoundedIcon /> },
    { to: '/settings', label: t('navigation.settings'), icon: <SettingsRoundedIcon /> },
  ];

  return (
    <Box sx={{ minHeight: '100vh' }}>
      <AppBar
        position="fixed"
        elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider', backdropFilter: 'blur(18px)' }}
      >
        <Toolbar>
          <Typography variant="h6" fontWeight={900} sx={{ letterSpacing: '-0.04em' }}>
            Panic Scanner
          </Typography>
        </Toolbar>
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          display: { xs: 'none', md: 'block' },
          width: drawerWidth,
          '& .MuiDrawer-paper': {
            width: drawerWidth,
            pt: 9,
            px: 2,
            backgroundImage:
              'linear-gradient(180deg, rgba(12,18,27,0.98), rgba(5,8,13,0.98))',
          },
        }}
      >
        <Stack spacing={1}>
          {items.map((item) => (
            <Button
              key={item.to}
              component={NavLink}
              to={item.to}
              startIcon={item.icon}
              sx={{
                justifyContent: 'flex-start',
                color: 'text.secondary',
                '&.active': {
                  color: 'primary.main',
                  bgcolor: 'rgba(34, 211, 238, 0.1)',
                },
              }}
            >
              {item.label}
            </Button>
          ))}
        </Stack>
      </Drawer>
      <Box component="main" sx={{ pt: 10, ml: { md: `${drawerWidth}px` }, pb: 6 }}>
        <Container maxWidth="xl">
          <Outlet />
        </Container>
      </Box>
    </Box>
  );
}
