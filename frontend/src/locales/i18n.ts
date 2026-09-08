import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';

import enUS from '@/locales/en-US.json';
import ptBR from '@/locales/pt-BR.json';

void i18next.use(initReactI18next).init({
  resources: {
    'en-US': { translation: enUS },
    'pt-BR': { translation: ptBR },
  },
  lng: localStorage.getItem('panic-scanner-language') ?? 'pt-BR',
  fallbackLng: 'en-US',
  interpolation: { escapeValue: false },
});

export default i18next;
