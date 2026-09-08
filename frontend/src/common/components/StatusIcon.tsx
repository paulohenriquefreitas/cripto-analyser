import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import ErrorRoundedIcon from '@mui/icons-material/ErrorRounded';
import HourglassBottomRoundedIcon from '@mui/icons-material/HourglassBottomRounded';
import RemoveCircleRoundedIcon from '@mui/icons-material/RemoveCircleRounded';

type StatusIconProps = {
  status: string;
};

export function StatusIcon({ status }: StatusIconProps) {
  if (status.includes('READY') || status.includes('CONFIRMED') || status === 'QUALIFIED') {
    return <CheckCircleRoundedIcon color="success" fontSize="small" />;
  }
  if (status.includes('WAIT') || status.includes('PROGRESS') || status === 'WATCH') {
    return <HourglassBottomRoundedIcon color="warning" fontSize="small" />;
  }
  if (status.includes('INVALID') || status.includes('REJECTED') || status.includes('WEAKENING')) {
    return <ErrorRoundedIcon color="error" fontSize="small" />;
  }
  return <RemoveCircleRoundedIcon color="disabled" fontSize="small" />;
}
