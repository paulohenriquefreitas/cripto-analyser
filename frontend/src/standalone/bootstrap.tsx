import React, { type ComponentType } from 'react';
import ReactDOM from 'react-dom/client';
import { AppProviders } from '../app/providers/AppProviders';
import '../app/theme/global.css';
import '../locales/i18n';

/** Shared presentation bootstrap; deliberately does not import the monolith router. */
export function bootstrap(Page: ComponentType) {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <AppProviders><Page /></AppProviders>
    </React.StrictMode>,
  );
}
