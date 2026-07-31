import { useEffect, useRef, useState } from 'react';
import { createDecoder, hasNativeDecoder } from './decoder';
import type { FrameDecoder } from './decoder';

/**
 * Видоискатель сканера.
 *
 * <p>Открывается поверх экрана и закрывается сам, как только код прочитан:
 * приёмщик держит деталь в одной руке, и лишнее подтверждение здесь — лишнее
 * действие. Ошибочное чтение исправляется тем же сканированием, а
 * не диалогом.
 *
 * <p><b>Кадры разбираются не чаще пяти раз в секунду.</b> Распознавание —
 * самая тяжёлая операция в приложении, и на каждом кадре видео оно съест
 * батарею и нагреет телефон, ничего не ускорив: наводят руками, и за 200 мс
 * картинка меняется незначительно.
 *
 * <p><b>Камера отпускается при закрытии обязательно.</b> Незакрытый поток —
 * это горящий индикатор камеры и разряд телефона до вечера; на чужом
 * устройстве это ещё и повод удалить приложение.
 */

/** Ниже этого разрешения Code128 на этикетке не читается. */
const IDEAL_WIDTH = 1280;
const FRAME_INTERVAL_MS = 200;

export interface ScanOverlayProps {
  onScan: (text: string) => void;
  onClose: () => void;
  /** Подпись: что именно ждём — ячейку, VIN. */
  hint?: string;
}

export function ScanOverlay({ onScan, onClose, hint }: ScanOverlayProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // Через ref, а не через состояние: колбэк живёт внутри цикла кадров,
  // который не должен пересоздаваться при каждом рендере.
  const onScanRef = useRef(onScan);
  onScanRef.current = onScan;

  useEffect(() => {
    let stream: MediaStream | null = null;
    let decoder: FrameDecoder | null = null;
    let timer: number | undefined;
    let stopped = false;

    async function start(): Promise<void> {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          // exact не ставим: на телефонах без задней камеры (и в браузере
          // на ноутбуке) exact — это отказ, а ideal — просто другая камера.
          video: { facingMode: { ideal: 'environment' }, width: { ideal: IDEAL_WIDTH } },
        });
        if (stopped) {
          return;
        }
        const video = videoRef.current;
        if (video === null) {
          return;
        }
        video.srcObject = stream;
        await video.play();

        decoder = await createDecoder();
        setLoading(false);

        const canvas = document.createElement('canvas');
        const context = canvas.getContext('2d', { willReadFrequently: true });

        timer = window.setInterval(() => {
          void grab();
        }, FRAME_INTERVAL_MS);

        async function grab(): Promise<void> {
          if (stopped || decoder === null || context === null) {
            return;
          }
          const source = videoRef.current;
          if (source === null || source.videoWidth === 0) {
            return;
          }
          canvas.width = source.videoWidth;
          canvas.height = source.videoHeight;
          context.drawImage(source, 0, 0);

          const text = await decoder.decode(canvas);
          if (text !== null && !stopped) {
            // Гасим цикл прямо здесь: пока всплывает обработчик, придёт ещё
            // два-три кадра с тем же кодом, и ячейка сменится дважды.
            stopped = true;
            navigator.vibrate?.(50);
            onScanRef.current(text);
          }
        }
      } catch (cause) {
        setLoading(false);
        setError(describe(cause));
      }
    }

    void start();

    return () => {
      stopped = true;
      window.clearInterval(timer);
      decoder?.dispose();
      // Именно каждую дорожку: остановка потока целиком не гасит камеру.
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  return (
    <div className="scan-overlay">
      <video ref={videoRef} playsInline muted className="scan-video" />

      <div className="scan-frame" />

      <div className="scan-bar">
        {error !== null ? (
          <span className="scan-error">{error}</span>
        ) : (
          <span>{loading ? 'Запуск камеры…' : (hint ?? 'Наведите на штрихкод')}</span>
        )}
        <button type="button" onClick={onClose}>
          Отмена
        </button>
      </div>
    </div>
  );
}

/**
 * Причина отказа камеры человеческим языком.
 *
 * <p>Штатное «NotAllowedError» приёмщику не говорит ничего, а причин ровно три,
 * и у каждой своё действие. Отдельно важен незащищённый источник: по http
 * камеры не будет никогда, и это ошибка развёртывания, а не телефона.
 */
function describe(cause: unknown): string {
  const name = cause instanceof Error ? cause.name : '';
  if (name === 'NotAllowedError') {
    return 'Доступ к камере запрещён. Разрешите его в настройках браузера.';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'Камера не найдена.';
  }
  if (!window.isSecureContext) {
    return 'Камера работает только по https.';
  }
  if (!hasNativeDecoder() && !navigator.onLine) {
    return 'Распознаватель не загружен, а связи нет. Введите код руками.';
  }
  return 'Камера недоступна. Введите код руками.';
}
