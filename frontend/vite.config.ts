import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Прокси на бэкенд обязателен, а не удобен.
 *
 * Сессия живёт в cookie, а CSRF-токен приложение читает из cookie скриптом.
 * И то и другое работает только когда фронтенд и API на одном источнике.
 * Ходить с localhost:5173 напрямую на localhost:8080 значит получить
 * межсайтовый запрос: cookie не отправится, токен не прочитается.
 *
 * В бою фронтенд отдаётся тем же приложением или тем же доменом через
 * обратный прокси — по той же причине.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
