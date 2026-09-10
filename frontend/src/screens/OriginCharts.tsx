import { useEffect, useState } from 'react';

import { ApiError } from '../api/client';
import { useLockedScroll } from '../ui/useLockedScroll';
import { useMounted } from '../ui/useMounted';
import { axisMonth, donorChart, money, supplyChart } from '../reports/reports';
import type { ChartPoint, OriginChart } from '../reports/reports';
import { count } from '../ui/plural';

/**
 * Окупаемость машины и партии во времени: два графика в окне поверх экрана.
 *
 * <p><b>Зачем.</b> Таблица «Окупаемость машин» отвечает «окупилась ли
 * на сегодня», а владелец решает по другому вопросу: «за сколько». Машина,
 * отбившая вложенное за два месяца, и машина, отбившая столько же за два
 * года, в таблице выглядят одинаково — а брать ли следующий контейнер,
 * решают именно по этой разнице.
 *
 * <p><b>Рисуется своим SVG, а не библиотекой.</b> Две линии и столбцы
 * на месячной сетке — это триста строк, а самая скромная библиотека
 * графиков весит больше всего нынешнего фронтенда, у которого зависимостей
 * ровно три. Правило проекта — «прежде чем добавить зависимость, назовите,
 * что она убирает»: убрала бы она этот файл, а принесла бы обновления,
 * уязвимости и чужие решения о палитре и поведении на телефоне — при том
 * что палитра у нас снята с кабинета клиента, а поведение на узком экране
 * проверяется своим сторожем. Цена решения названа честно: подписи осей,
 * выбор шага и всплывающие значения написаны здесь руками.
 *
 * <p><b>Всплывающее значение — родное `title` браузера, а не своя
 * подсказка.</b> Оно работает и мышью, и с клавиатуры, и не требует ни
 * строчки на позиционирование — а позиционирование своей подсказки внутри
 * прокручиваемой обёртки уже стоило нам живого прогона на экране выгрузок.
 */
interface Props {
  /** Что показываем: машина или партия. Партия с пустым номером — «не указана». */
  origin: { kind: 'donor'; id: number } | { kind: 'supply'; id: number | null };
  /** Чем подписано окно: та же подпись, что выбрана в разрезе. */
  title: string;
  onClose: () => void;
}

/** Ряды первого графика — в том же порядке и теми же словами, что в легенде. */
const SERIES: Array<{
  key: 'planned' | 'revenue' | 'soldCost' | 'totalCost';
  name: string;
  color: string;
  dashed: boolean;
}> = [
  { key: 'planned', name: 'Планируемая сумма', color: '#2e90fa', dashed: true },
  { key: 'revenue', name: 'Продано на сумму', color: '#f79009', dashed: false },
  { key: 'soldCost', name: 'Себестоимость проданных', color: '#12b76a', dashed: false },
  { key: 'totalCost', name: 'Себестоимость всех', color: '#eaaa08', dashed: true },
];

export function OriginCharts({ origin, title, onClose }: Props) {
  const [chart, setChart] = useState<OriginChart | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mounted = useMounted();

  useLockedScroll();

  useEffect(() => {
    setChart(null);
    setError(null);
    const load = origin.kind === 'donor' ? donorChart(origin.id) : supplyChart(origin.id);
    load.then(
      (loaded) => {
        if (mounted.current) {
          setChart(loaded);
        }
      },
      (cause: unknown) => {
        if (mounted.current) {
          setError(describe(cause));
        }
      },
    );
  }, [origin.kind, origin.id, mounted]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="modal modal--charts"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="row row--between">
          <h2>Графики · {title}</h2>
          <button type="button" className="button--ghost" onClick={onClose}>✕</button>
        </div>

        {/* Три состояния различимы: грузим, не смогли узнать, пусто. */}
        {error !== null && <p className="note note--error">{error}</p>}
        {chart === null && error === null && <p className="note">Загружаем…</p>}

        {chart !== null && chart.points.length === 0 && (
          <p className="note">
            По этому разрезу пока ничего нет: ни затрат, ни поступлений,
            ни продаж — рисовать нечего.
          </p>
        )}

        {chart !== null && chart.points.length > 0 && (
          <>
            <h3>Выручка</h3>
            <Legend />
            <div className="chart-scroll">
              <LineChart points={chart.points} />
            </div>

            <h3>Продажи по месяцам</h3>
            <p className="chart-legend">
              <span className="chart-swatch" style={{ borderTopColor: '#12b76a' }} />
              Продано на сумму
            </p>
            <div className="chart-scroll">
              <BarChart points={chart.points} />
            </div>

            {/* Полка накопительной линии и есть ответ «выдохлась или ещё
                продаётся», но словами он всё равно нужен: график читают
                глазами, а число сверяют с таблицей окупаемости. */}
            <p className="note">{summary(chart.points)}</p>
          </>
        )}
      </div>
    </div>
  );
}

function Legend() {
  return (
    <p className="chart-legend">
      {SERIES.map((series) => (
        <span key={series.key} className="chart-legend__item">
          <span
            className={series.dashed ? 'chart-swatch chart-swatch--dashed' : 'chart-swatch'}
            style={{ borderTopColor: series.color }}
          />
          {series.name}
        </span>
      ))}
    </p>
  );
}

/** Ширина месяца на оси: уже — и подписи налезают друг на друга. */
const STEP = 46;

const LEFT = 68;

const RIGHT = 12;

const TOP = 10;

const HEIGHT = 220;

const BOTTOM = 26;

function LineChart({ points }: { points: ChartPoint[] }) {
  const top = Math.max(
    ...points.map((p) => Math.max(p.planned, p.revenue, p.soldCost, p.totalCost)),
  );
  const scale = new Scale(points.length, top);

  return (
    <svg width={scale.width} height={HEIGHT} role="img" aria-label="Выручка по месяцам">
      <Grid scale={scale} points={points} />
      {SERIES.map((series) => (
        <polyline
          key={series.key}
          fill="none"
          stroke={series.color}
          strokeWidth={2}
          strokeDasharray={series.dashed ? '6 4' : undefined}
          points={points
            .map((point, at) => `${scale.x(at)},${scale.y(point[series.key])}`)
            .join(' ')}
        />
      ))}
      {points.map((point, at) => (
        <rect
          key={point.month}
          x={scale.x(at) - scale.gap / 2}
          y={TOP}
          width={scale.gap}
          height={HEIGHT - TOP - BOTTOM}
          fill="transparent"
        >
          <title>{tooltip(point)}</title>
        </rect>
      ))}
    </svg>
  );
}

function BarChart({ points }: { points: ChartPoint[] }) {
  const top = Math.max(...points.map((p) => p.monthRevenue));
  const scale = new Scale(points.length, top);
  const width = Math.min(scale.gap - 10, 22);

  return (
    <svg
      width={scale.width}
      height={HEIGHT}
      role="img"
      aria-label="Продажи по месяцам"
    >
      <Grid scale={scale} points={points} />
      {points.map((point, at) => (
        <rect
          key={point.month}
          x={scale.x(at) - width / 2}
          y={scale.y(point.monthRevenue)}
          width={width}
          height={Math.max(0, scale.y(0) - scale.y(point.monthRevenue))}
          fill="#12b76a"
        >
          <title>{`${axisMonth(point.month)}: продано на ${money(point.monthRevenue)}`}</title>
        </rect>
      ))}
    </svg>
  );
}

/** Сетка, подписи рублей слева и месяцев снизу — одна на оба графика. */
function Grid({ scale, points }: { scale: Scale; points: ChartPoint[] }) {
  // Подпись у каждого месяца на длинной оси превращается в кашу: показываем
  // каждую n-ю, но первый и последний месяц — всегда, по ним и читают,
  // «от октября 22-го до сентября 26-го».
  const every = Math.max(1, Math.ceil(points.length / 12));

  return (
    <g>
      {scale.ticks().map((value) => (
        <g key={value}>
          <line
            x1={LEFT}
            x2={scale.width - RIGHT}
            y1={scale.y(value)}
            y2={scale.y(value)}
            stroke="#e4e7ec"
          />
          <text x={LEFT - 8} y={scale.y(value) + 4} textAnchor="end" className="chart-tick">
            {count(value)}
          </text>
        </g>
      ))}
      {points.map((point, at) => (
        (at % every === 0 || at === points.length - 1) && (
          <text
            key={point.month}
            x={scale.x(at)}
            y={HEIGHT - 8}
            textAnchor="middle"
            className="chart-tick"
          >
            {axisMonth(point.month)}
          </text>
        )
      ))}
    </g>
  );
}

/**
 * Пересчёт денег и месяцев в пиксели.
 *
 * <p>Верх шкалы округляется до «круглого» числа, а не берётся максимумом
 * ряда: иначе верхняя подпись оси гласит «117 300» — читать по такой сетке
 * нечего.
 */
class Scale {
  readonly width: number;

  /** Ширина одного месяца: по ней же ставится и столбец, и область наведения. */
  readonly gap: number;

  private readonly top: number;

  private readonly step: number;

  constructor(months: number, top: number) {
    // Нижний предел — под ширину окна на мониторе (52rem при кабинетных
    // 13 пикселях это ≈ 640 внутри полей): иначе график на семи месяцах
    // жмётся к левому краю, а справа остаётся пустая половина окна.
    // На телефоне окно у́же, и график прокручивается внутри своей обёртки —
    // ровно так же, как таблица на витрине.
    this.width = Math.max(620, LEFT + RIGHT + months * STEP);
    this.gap = (this.width - LEFT - RIGHT) / Math.max(1, months);
    this.step = niceStep(top);
    // Ноль — законное состояние: у машины без продаж весь второй график
    // лежит на нуле, и делить на него нельзя.
    this.top = this.step * Math.max(4, Math.ceil(top / this.step));
  }

  x(at: number): number {
    return LEFT + this.gap * (at + 0.5);
  }

  y(value: number): number {
    const plot = HEIGHT - TOP - BOTTOM;
    return TOP + plot - (value / this.top) * plot;
  }

  ticks(): number[] {
    const marks: number[] = [];
    for (let value = 0; value <= this.top + 0.5; value += this.step) {
      marks.push(Math.round(value));
    }
    return marks;
  }
}

/**
 * Шаг сетки: 1, 2, 2,5 или 5 на порядок — то, чем считают деньги.
 *
 * <p>У ориентира на оси стоит шаг 10 000; выбирать его числом нельзя —
 * на контейнере в три миллиона это триста линий.
 */
function niceStep(top: number): number {
  if (!(top > 0)) {
    return 1000;
  }
  const rough = top / 4;
  const order = 10 ** Math.floor(Math.log10(rough));
  for (const factor of [1, 2, 2.5, 5]) {
    if (rough <= factor * order) {
      return factor * order;
    }
  }
  return 10 * order;
}

function tooltip(point: ChartPoint): string {
  return `${axisMonth(point.month)}\n`
    + SERIES.map((series) => `${series.name}: ${money(point[series.key])}`).join('\n');
}

/** Итог словами: последняя точка и месяц, в котором выручка перешла вложенное. */
function summary(points: ChartPoint[]): string {
  const last = points[points.length - 1];
  if (last === undefined) {
    return '';
  }
  const payback = points.find((point) => point.totalCost > 0
    && point.revenue >= point.totalCost);

  return `Вложено ${money(last.totalCost)} · продано на ${money(last.revenue)} · `
    + (payback === undefined
      ? 'ещё не окупилась'
      : `окупилась в ${axisMonth(payback.month)}`);
}

function describe(cause: unknown): string {
  if (cause instanceof ApiError) {
    if (cause.status === 0) {
      return 'Нет связи с сервером. График считается на сервере — повторите.';
    }
    if (cause.status === 403) {
      return 'Графики видит владелец или менеджер';
    }
    return cause.message;
  }
  return 'Не удалось построить график';
}
