import { AppBar, Box, Button, Container, Stack, Toolbar, Typography } from '@mui/material';
import { NavLink, Outlet } from 'react-router-dom';

export function StandaloneLayout({ title, links }: { title: string; links: { to: string; label: string }[] }) {
  return <>
    <AppBar position="static"><Toolbar><Typography variant="h6">{title}</Typography></Toolbar></AppBar>
    <Container maxWidth="xl" sx={{ py: 2 }}>
      <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
        {links.map(link => <Button key={link.to} component={NavLink} to={link.to}>{link.label}</Button>)}
      </Stack>
      <Box component="main"><Outlet /></Box>
    </Container>
  </>;
}
