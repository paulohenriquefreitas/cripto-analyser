import { List, ListItem, ListItemText } from '@mui/material';

type ReasonListProps = {
  reasons: string[];
};

export function ReasonList({ reasons }: ReasonListProps) {
  return (
    <List dense>
      {reasons.map((reason) => (
        <ListItem key={reason} disableGutters>
          <ListItemText primary={reason} />
        </ListItem>
      ))}
    </List>
  );
}
