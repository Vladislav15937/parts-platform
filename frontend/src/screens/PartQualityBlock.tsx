import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { QUALITY_GRADES, qualityTitle, savePartsBulk } from '../inventory/catalog';
import { useMounted } from '../ui/useMounted';

/**
 * Оценка состояния — с самой карточки, одним нажатием.
 *
 * <p><b>Почему не в форме правки.</b> Оценка уходит в объявление и стоит
 * там сразу после цены и снимка, а поставить её можно было только открыв
 * форму правки и найдя нужную строку среди двадцати с лишним. Хуже того,
 * поставить её было нельзя вовсе: форма предлагала четыре значения,
 * которых сервер не знает, и вся правка отваливалась ответом «Запрос
 * не разобран» — вместе с ценой и комментарием, набранными в тот же заход
 * (задача 0033).
 *
 * <p><b>У каждого пункта стоит пояснение, а не только название.</b>
 * «С дефектами» и «Требует ремонт» различает не словарь, а признак,
 * и без него приёмщик выбирает наугад — а выбранное читает покупатель.
 *
 * <p><b>Карточка при этом не закрывается.</b> Остальные действия карточки
 * (списание, перевозка, правка) закрывают её и перечитывают витрину, потому
 * что меняют остаток. Здесь меняется одно поле, и плашка обязана показать,
 * что именно выбрано, — иначе нажатие отвечает исчезновением окна. Витрину
 * за карточкой перечитает её закрытие: `PartCard` знает, что оценку трогали.
 */
export function PartQualityBlock({ partId, grade, role, onGraded }: {
  partId: number;
  /** Оценка, какой её знает строка витрины; `null` — «не оценена». */
  grade: string | null;
  role: string;
  /** Оценка записана: карточке это нужно, чтобы обновить плашку и витрину. */
  onGraded: (grade: string | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  // Карточку пересоздают новой строкой, но список остаётся раскрытым:
  // открыв соседнюю позицию, владелец увидел бы раскрытый чужой выбор.
  useEffect(() => {
    setOpen(false);
    setError('');
  }, [partId]);

  // Оценку ставит тот же, кто правит карточку: она уходит в объявление.
  // Остальным плашка показывается как есть, а кнопки нет вовсе — кнопка,
  // отвечающая отказом, хуже отсутствующей.
  const canGrade = role === 'OWNER' || role === 'MANAGER';
  const title = qualityTitle(grade);

  async function choose(next: string | null): Promise<void> {
    setError('');
    setSaving(true);
    try {
      // Правкой одной позиции списком, а не своим эндпоинтом: там уже
      // есть и запись в журнал изменений, и отметка для площадки,
      // и та же проверка роли. Второй путь к тому же полю разошёлся бы
      // с этим на первой правке.
      await savePartsBulk([partId], { qualityGrade: next });
      if (mounted.current) {
        setOpen(false);
        onGraded(next);
      }
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError && cause.message !== ''
          ? cause.message
          : 'Не удалось записать оценку');
      }
    } finally {
      if (mounted.current) {
        setSaving(false);
      }
    }
  }

  // Роли без права у неоценённой детали показывать нечего: пустая плашка
  // над снимком — место, занятое ничем.
  if (!canGrade && title === '') {
    return null;
  }

  return (
    <div className="quality">
      {title !== '' && (
        <span className={`quality__mark quality__mark--${grade}`}>{title}</span>
      )}

      {canGrade && (
        <button
          type="button"
          className="button--ghost"
          disabled={saving}
          onClick={() => setOpen((was) => !was)}
        >
          {saving ? 'Записываем…'
                  : title === '' ? 'Оценить запчасть'
                  : 'Изменить оценку'}
        </button>
      )}

      {error !== '' && <p className="note note--error">{error}</p>}

      {canGrade && open && (
        <div className="quality__list">
          {QUALITY_GRADES.map((option) => (
            <button
              key={option.key}
              type="button"
              className={option.key === grade
                ? `quality__option quality__option--${option.key} is-chosen`
                : `quality__option quality__option--${option.key}`}
              disabled={saving}
              onClick={() => void choose(option.key)}
            >
              <span className="quality__title">{option.title}</span>
              <span className="quality__hint">{option.hint}</span>
            </button>
          ))}

          {/* Снять оценку можно только с оценённой — у неоценённой этот
              пункт означал бы действие без последствий. Пишется NULL,
              а не пустая строка: пусто здесь значит «не оценена». */}
          {grade !== null && grade !== '' && (
            <button
              type="button"
              className="quality__option quality__option--clear"
              disabled={saving}
              onClick={() => void choose(null)}
            >
              <span className="quality__title">Снять оценку</span>
              <span className="quality__hint">Деталь вернётся к «не оценена».</span>
            </button>
          )}

          <p className="quality__note">
            Оценка уходит в объявления на площадки — ставьте ту,
            которая сейчас верна.
          </p>
        </div>
      )}
    </div>
  );
}
