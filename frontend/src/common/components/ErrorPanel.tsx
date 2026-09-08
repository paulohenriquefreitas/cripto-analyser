import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';
import { Alert, AlertTitle } from '@mui/material';

type ErrorPanelProps = {
  title: string;
  detail: string;
};

export function ErrorPanel({ title, detail }: ErrorPanelProps) {
  return (
    <Alert severity="error" icon={<ErrorOutlineRoundedIcon />}>
      <AlertTitle>{title}</AlertTitle>
      {detail}
    </Alert>
  );
}
