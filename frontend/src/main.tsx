import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './app.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('Нет корневого элемента: index.html повреждён');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

/**
 * Service Worker нужен ради офлайн-старта: без него приложение при отсутствии
 * связи просто не откроется, а значит станет недоступна и очередь отправки —
 * то есть работа приёмщика, уже сделанная, но не ушедшая.
 *
 * Регистрируется только в проде: в разработке кэш оболочки мешает видеть
 * изменения и заставляет чистить хранилище руками.
 */
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((error: unknown) => {
      // Отсутствие офлайн-режима — не повод не работать онлайн.
      console.warn('Service Worker не зарегистрирован', error);
    });
  });
}
