import { MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';

import { env } from '@/common/api/env';
import { useColorMode } from '@/common/hooks/colorModeContext';

export function SettingsPage() {
  const { t, i18n } = useTranslation();
  const { mode, setMode } = useColorMode();

  return (
    <Stack spacing={3} maxWidth={720}>
      <Stack spacing={0.5}>
        <Typography variant="h3">{t('settings.title')}</Typography>
        <Typography color="text.secondary">{t('settings.subtitle')}</Typography>
      </Stack>
      <Paper sx={{ p: 3 }}>
        <Stack spacing={3}>
          <TextField
            select
            label={t('settings.theme')}
            value={mode}
            onChange={(event) => setMode(event.target.value === 'light' ? 'light' : 'dark')}
          >
            <MenuItem value="dark">{t('settings.dark')}</MenuItem>
            <MenuItem value="light">{t('settings.light')}</MenuItem>
          </TextField>
          <TextField
            select
            label={t('settings.language')}
            value={i18n.language}
            onChange={(event) => {
              localStorage.setItem('panic-scanner-language', event.target.value);
              void i18n.changeLanguage(event.target.value);
            }}
          >
            <MenuItem value="en-US">English</MenuItem>
            <MenuItem value="pt-BR">Português</MenuItem>
          </TextField>
          <TextField label={t('settings.apiUrl')} value={env.apiUrl} slotProps={{ input: { readOnly: true } }} />
          <TextField
            label={t('settings.apiMode')}
            value={env.useMockApi ? 'Mock' : 'Backend'}
            slotProps={{ input: { readOnly: true } }}
          />
        </Stack>
      </Paper>
    </Stack>
  );
}
