# Panic Scanner Frontend

Production-oriented React + TypeScript frontend for the Panic Scanner Spring Boot API.

## Stack

- React 19
- TypeScript
- Vite
- React Router
- TanStack Query
- Axios
- Material UI + MUI Data Grid
- React Hook Form + Zod
- Recharts
- i18next
- DayJS
- ESLint + Prettier

Redux is intentionally not used. Server state belongs to TanStack Query. Local UI preferences use small React Context providers.

## Architecture

The app uses a feature-based structure:

```text
src/
  app/
    router/       route definitions
    providers/    global runtime providers
    theme/        MUI theme and global CSS
  common/
    api/          shared HTTP client and API error handling
    components/   reusable UI building blocks
    hooks/        cross-feature hooks
    layout/       app shell
    utils/        formatting and helpers
  features/
    scanner/      market scan API, hooks, models, pages and widgets
    stock/        stock detail route
    dashboard/    dashboard route
    settings/     local app settings
  locales/        i18n resources
```

Feature code should expose typed services/hooks. Components must not call Axios directly.

## Running

```bash
npm install
npm run dev
```

The app runs on `http://localhost:5173`.

## Backend URL

Create a local `.env` file:

```bash
VITE_API_URL=http://localhost:8080
VITE_USE_MOCK_API=false
```

Default mode is mock API, so the UI can run before the backend is available.

## API Integration

The scanner feature consumes:

- `POST /api/panic-scanner/market-scan`
- `POST /api/panic-scanner/analyze/{ticker}`

Backend enum values remain English. Frontend translations are handled in `src/locales/*.json`.

## Adding Pages

1. Create a page under `src/features/<feature>/pages`.
2. Add feature-local API/hooks/models if the page needs data.
3. Register the route in `src/app/router/AppRouter.tsx`.
4. Add navigation labels to both locale files.

## Adding Translations

Add keys to both:

- `src/locales/en-US.json`
- `src/locales/pt-BR.json`

Do not request translated enum values from the backend.

## Future Features

The current structure leaves space for:

- Backtesting
- Portfolio Manager
- Alert Center
- Watchlists
- Replacement of Recharts with TradingView-compatible chart modules

Keep those features isolated under `src/features/<feature>` and share only stable cross-cutting utilities through `src/common`.
