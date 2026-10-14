import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

import { PartEditForm, changedNote } from './PartEditForm';
import type { CatalogRow } from '../inventory/catalog';

/**
 * Карточка правится по одному полю, и видно, что именно ты меняешь.
 *
 * <p><b>Зачем.</b> Нажатие «Изменить» превращало карточку в форму целиком:
 * четыре блока, больше двадцати полей ввода, все одинаковые и до правки,
 * и после. Правят карточку точечно — цену подвинуть, комментарий дописать, —
 * а форма уезжает PUT'ом целиком, и пустое поле означает «очистить»:
 * случайно задетое поле уходит молча вместе со всем, что в нём было
 * написано, и отменить это нечем.
 *
 * <p>Проверяется то, что видит человек: сколько на экране полей ввода,
 * что написано в счётчике и что уехало на сервер, — а не то, какое
 * состояние держит компонент.
 */
describe('правка карточки по одному полю', () => {
  let sent: Array<Record<string, unknown>>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({ price: 27000 });
      }
      void input;
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('открывается значениями, а не двумя десятками полей ввода', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByText('скол на креплении')).toBeTruthy());

    // До этой задачи здесь открывалось больше двадцати полей сразу — испортить
    // можно было любое, и на экране это никак не отмечалось.
    expect(fields().length, 'форма открылась полями ввода').toBe(0);
    expect(line('Производитель').textContent).toContain('Toyota');
    // Пустое поле названо словами: прочерк читается как «не знаем»,
    // а тут мы знаем — значение просто не заполнено.
    expect(screen.getAllByText('не заполнено').length).toBeGreaterThan(0);
  });

  it('раскрывает только ту строку, у которой нажали «Изменить»', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Цена'));

    expect(screen.getByDisplayValue('27000')).toBeTruthy();
    expect(screen.queryByLabelText('Заметка'), 'раскрылась и соседняя строка').toBeNull();
    expect(line('Цена').className, 'изменённая строка ничем не отмечена')
      .toContain('card-edit__row--changed');
    expect(screen.getByText('Изменен 1 параметр')).toBeTruthy();
    expect(save().disabled, 'изменение есть, а «Сохранить» погашена').toBe(false);
  });

  it('считает изменённое и склоняет слово при числе', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Цена'));
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    expect(screen.getByText('Изменено 2 параметра')).toBeTruthy();

    fireEvent.click(screen.getByLabelText('Изменить: Цвет'));
    expect(screen.getByText('Изменено 3 параметра')).toBeTruthy();
  });

  it('«Отменить» у строки возвращает её значение, а не всю форму', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Цена'));
    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.click(screen.getByLabelText('Изменить: Цвет'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });

    fireEvent.click(screen.getByLabelText('Отменить: Заметка'));

    // Строка вернулась к тексту с прежним значением…
    expect(screen.queryByLabelText('Заметка')).toBeNull();
    expect(screen.getByText('скол на креплении')).toBeTruthy();
    expect(screen.getByText('Изменено 2 параметра')).toBeTruthy();
    // …а две другие остались раскрытыми: отменяется одна строка, а не форма.
    expect(screen.getByDisplayValue('27000')).toBeTruthy();
    expect(screen.getByLabelText('Цвет')).toBeTruthy();

    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['note'], 'отменённая строка всё равно уехала изменённой')
      .toBe('скол на креплении');
  });

  it('уезжает вся форма, а изменены ровно тронутые поля', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: 'трещина' } });
    fireEvent.click(screen.getByLabelText('Изменить: Маркировка'));
    fireEvent.change(screen.getByLabelText('Маркировка'), { target: { value: '81170-05' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    const body = sent[0]!;
    expect(body['note']).toBe('трещина');
    expect(body['marking']).toBe('81170-05');
    // Остальное уехало таким же, каким пришло: PUT целиком не значит
    // «перетереть нетронутое».
    expect(body['price']).toBe(27000);
    expect(body['priceOp'], 'к нетронутой цене применилась арифметика').toBe('SET');
    expect(body['description']).toBe('Фара в сборе');
    expect(body['manufacturer']).toBe('Toyota');
    expect(body['published']).toBe(true);
  });

  it('ничего не изменено — «Сохранить» погашена и говорит почему', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    expect(save().disabled).toBe(true);
    expect(screen.getByText(/Менять нечего/), 'кнопка погашена и молчит').toBeTruthy();

    fireEvent.click(save());
    expect(sent.length, 'погашенная кнопка всё-таки сохранила').toBe(0);
  });

  it('стёртое руками поле по-прежнему очищается', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Заметка')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Заметка'));
    fireEvent.change(screen.getByLabelText('Заметка'), { target: { value: '' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    // Стереть заметку — законное действие, ради него PUT и уезжает целиком.
    expect(sent[0]!['note']).toBeNull();
  });

  it('неправимое поле показано с замком и нажатием не раскрывается', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    // Спрятанное поле читается как «этого у вас нет», поэтому оно на месте —
    // просто не раскрывается: номер товара система ведёт сама.
    const code = line('Номер товара');
    expect(code.textContent).toContain('🔒');
    expect(code.textContent).toContain('B-40219');
    expect(code.querySelector('button'), 'у неправимой строки есть «Изменить»').toBeNull();

    // Донорские сведения — оттуда же: они приезжают с машины, а не с детали.
    expect(line('Номер донора').textContent).toContain('261');
    expect(line('Марка').querySelector('button')).toBeNull();
  });

  it('владелец меняет состояние выбором из списка, и уезжает код', async () => {
    // До задачи 0039 эта строка стояла с замком: состояние писалось один
    // раз, в приёмке, и «б/у» вместо «новой» исправлялось только заведением
    // второй карточки.
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Состояние')).toBeTruthy());

    expect(line('Состояние').textContent, 'состояние показано кодом').toContain('б/у');
    fireEvent.click(screen.getByLabelText('Изменить: Состояние'));
    fireEvent.change(screen.getByLabelText('Состояние'), { target: { value: 'CONTRACT' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['condition']).toBe('CONTRACT');
  });

  it('менеджеру состояние показано с замком, а не спрятано', async () => {
    // Спрятанное поле читается как «этого у вас нет», а состояние у детали
    // есть — его просто меняет владелец (пункт 6 критерия 0039).
    render(<PartEditForm partId={7} row={row()} role="MANAGER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());

    const condition = line('Состояние');
    expect(condition.textContent).toContain('б/у');
    expect(condition.textContent).toContain('🔒');
    expect(condition.querySelector('button'),
      'менеджеру предложено менять состояние — сервер это отобьёт').toBeNull();
  });
});

/**
 * Блоки карточки Дрома в форме правки (задача 0285): три поля словами Дрома
 * рядом с «Комментарием», многострочные, перевод строки виден.
 */
describe('блоки карточки Дрома', () => {
  let sent: Array<Record<string, unknown>>;

  beforeEach(() => {
    sent = [];
    vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') !== 'GET') {
        sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>);
        return json({ price: 27000 });
      }
      return json(card());
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('показаны подписями Дрома, с замком не стоят и сохраняют переводы строк', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Самовывоз')).toBeTruthy());

    for (const label of ['Гарантия и условия возврата', 'Самовывоз', 'Доставка и оплата']) {
      expect(line(label).textContent, `у «${label}» стоит замок`).not.toContain('🔒');
      expect(screen.getByLabelText(`Изменить: ${label}`), `«${label}» не правится`).toBeTruthy();
    }
    expect(line('Самовывоз').textContent).toContain('Самовывоз — улица Георгия Исакова 9');
    // Перевод строки лежит в тексте строки, а CSS оставляет его видимым.
    const delivery = line('Доставка и оплата').querySelector('.card-edit__value')!;
    expect(delivery.textContent).toBe('Доставка по городу — 300 р.\n\nОплата при получении');
  });

  it('изменённый блок уезжает тем же PUT, остальные блоки — как были', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Самовывоз')).toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Самовывоз'));
    expect((screen.getByLabelText('Самовывоз') as HTMLTextAreaElement).tagName)
      .toBe('TEXTAREA');
    fireEvent.change(screen.getByLabelText('Самовывоз'),
      { target: { value: 'Самовывоз — склад на Ленина 5' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['pickup']).toBe('Самовывоз — склад на Ленина 5');
    expect(sent[0]!['warranty']).toBe('Обговаривается индивидуально');
    expect(sent[0]!['deliveryPayment'])
      .toBe('Доставка по городу — 300 р.\n\nОплата при получении');
  });

  it('«Вид детали» и «Раздел» показаны подписями Дрома, правятся и стираются тем же PUT (задача 0286)', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Раздел')).toBeTruthy());

    expect(line('Вид детали').textContent).toContain('Карданный вал Lexus GX400');
    expect(line('Раздел').textContent).toContain('Автозапчасти → Трансмиссия');
    // «Секция» склада — другая строка, не «Раздел».
    expect(line('Секция').textContent).not.toContain('Автозапчасти');

    fireEvent.click(screen.getByLabelText('Изменить: Раздел'));
    fireEvent.change(screen.getByLabelText('Раздел'),
      { target: { value: 'Автозапчасти → Кузов' } });
    fireEvent.click(screen.getByLabelText('Изменить: Вид детали'));
    fireEvent.change(screen.getByLabelText('Вид детали'), { target: { value: '' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['dromSection']).toBe('Автозапчасти → Кузов');
    expect(sent[0]!['dromKind'], 'очистка — пустая строка, null сервер прочёл бы как «не трогать»')
      .toBe('');
    expect(sent[0]!['section'], '«Секция» склада поехала вместо «Раздела»').toBe(null);
  });

  it('«Подходит» показано блоками и свёрнуто; правка открывает текст как есть; очистка уезжает пустой строкой (задача 0287)', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Подходит')).toBeTruthy());

    const view = line('Подходит').querySelector('.fits')!;
    // Читаемо, а не сырой « | »: части через тире, пустая часть не оставляет « — » на конце.
    expect(view.textContent).toContain('Toyota 4Runner — 5 поколение (2009–2013) — TRN285, GRN285');
    expect(view.textContent).toContain('Lexus GX400 — GRJ158');
    expect(view.textContent).not.toContain('|');
    expect(view.textContent).not.toContain('GRJ158 —');
    // Свёрнуто: пять строк из восьми и кнопка с числом.
    expect(view.querySelectorAll('div').length).toBe(5);
    expect(view.textContent).not.toContain('Для двигателей');
    fireEvent.click(screen.getByText('Показать все (8 строк)'));
    expect(view.querySelectorAll('div').length).toBe(8);
    expect(view.textContent).toContain('Для двигателей — 1GR-FE, 2UZ-FE');
    fireEvent.click(screen.getByText('Свернуть'));
    expect(view.querySelectorAll('div').length).toBe(5);

    // Правка открывает хранимый текст как есть — с « | » и переводами строк.
    fireEvent.click(screen.getByLabelText('Изменить: Подходит'));
    expect((screen.getByLabelText('Подходит') as HTMLTextAreaElement).value).toBe(APPLICABILITY);
    fireEvent.change(screen.getByLabelText('Подходит'), { target: { value: '' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['applicability'], 'очистка — пустая строка, null сервер прочёл бы как «не трогать»')
      .toBe('');
  });

  it('«Характеристики» показаны «Подпись: значение» без [ключей] и служебных дублей; правка открывает текст как есть; очистка — пустая строка (задача 0292)', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Характеристики')).toBeTruthy());

    const view = line('Характеристики').querySelector('.fits')!;
    expect([...view.querySelectorAll('div')].map((d) => d.textContent)).toEqual([
      'Модель магнитолы: Toyota NHZT-W58',
      'Размер: 2 DIN — 178x100 мм',
    ]);
    expect(view.textContent).not.toContain('[');
    expect(view.textContent).not.toContain('model');

    // Правка открывает хранимое как есть — с [ключами] и служебной строкой.
    fireEvent.click(screen.getByLabelText('Изменить: Характеристики'));
    expect((screen.getByLabelText('Характеристики') as HTMLTextAreaElement).value).toBe(CHARACTERISTICS);
    fireEvent.change(screen.getByLabelText('Характеристики'), { target: { value: '' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['characteristics'], 'очистка — пустая строка, null сервер прочёл бы как «не трогать»')
      .toBe('');
  });

  it('нетронутые «Характеристики» уезжают как прочитаны, байт в байт', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Самовывоз'));
    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['characteristics']).toBe(CHARACTERISTICS);
  });

  it('нетронутое «Подходит» уезжает как прочитано, байт в байт', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Цена')).toBeTruthy());
    fireEvent.click(screen.getByLabelText('Изменить: Самовывоз'));
    fireEvent.click(save());
    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['applicability']).toBe(APPLICABILITY);
  });

  it('стёртый руками блок уезжает пустой строкой, а не null', async () => {
    render(<PartEditForm partId={7} row={row()} role="OWNER"
                         onSaved={() => {}} onCancel={() => {}} />);
    await waitFor(() => expect(screen.getByLabelText('Изменить: Гарантия и условия возврата'))
      .toBeTruthy());

    fireEvent.click(screen.getByLabelText('Изменить: Гарантия и условия возврата'));
    fireEvent.change(screen.getByLabelText('Гарантия и условия возврата'), { target: { value: '' } });
    fireEvent.click(save());

    await waitFor(() => expect(sent.length).toBe(1));
    expect(sent[0]!['warranty'], 'null сервер прочитал бы как «не трогать»').toBe('');
  });
});

/**
 * Склонение — по пяти числам, а не по одному: вторая десятка ломает
 * правило «по последней цифре», и «Изменено 11 параметр» владелец увидел бы
 * ровно тогда, когда правит много.
 */
describe('счётчик изменённого', () => {
  it('склоняет и глагол, и существительное', () => {
    expect(changedNote(1)).toBe('Изменен 1 параметр');
    expect(changedNote(2)).toBe('Изменено 2 параметра');
    expect(changedNote(5)).toBe('Изменено 5 параметров');
    expect(changedNote(21)).toBe('Изменен 21 параметр');
    expect(changedNote(11)).toBe('Изменено 11 параметров');
  });
});

/** Строка формы по подписи: у неправимой кнопки нет, и найти её иначе нечем. */
function line(label: string): HTMLElement {
  const found = [...document.querySelectorAll('.card-edit__row')].find(
    (r) => r.querySelector('.card-edit__label')?.textContent?.startsWith(label) === true);
  if (found === undefined) throw new Error(`строки «${label}» на экране нет`);
  return found as HTMLElement;
}

function fields(): Element[] {
  return [...document.querySelectorAll('.card-edit input, .card-edit select, .card-edit textarea')];
}

function save(): HTMLButtonElement {
  return screen.getByText('Сохранить') as HTMLButtonElement;
}

/** Магнитола из файла клиента (строка 211): первые две строки — один и тот же model. */
const CHARACTERISTICS = [
  'model [model]: Toyota NHZT-W58',
  'Модель магнитолы [model]: Toyota NHZT-W58',
  'Размер [size]: 2 DIN — 178x100 мм',
].join('\n');

/** Восемь пунктов: больше пяти — значит, свёрнуто. Пустая часть и «Для двигателей» — как у клиента. */
const APPLICABILITY = [
  'Lexus GX400 | GRJ158 | ',
  'Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285',
  'Toyota 4Runner | 4 поколение (2002–2005) | GRN215',
  'Toyota Hilux Surf | 4 поколение (2002–2005) | VZN215W',
  'Toyota Hilux Surf | Другие кузова | GRN215, VZN215',
  'Toyota Land Cruiser Prado | 4 поколение (2013–2017) | TRJ150W',
  'Toyota Land Cruiser Prado | Другие кузова | GRJ150L',
  'Для двигателей | 1GR-FE, 2UZ-FE',
].join('\n');

function card(): Record<string, unknown> {
  return {
    price: 27000, minPrice: null, costPrice: null, installationPrice: null,
    condition: 'USED',
    qualityGrade: 'NO_DEFECTS', description: 'Фара в сборе', note: 'скол на креплении',
    textBlock: null, videoUrl: null, marking: null, manufacturer: 'Toyota',
    color: null, section: null, barcode: null, weightKg: null, lengthMm: null,
    widthMm: null, heightMm: null, packageLengthMm: null, packageWidthMm: null,
    packageHeightMm: null, packageWeightKg: null, storageCellId: null,
    published: true,
    warranty: 'Обговаривается индивидуально',
    pickup: 'Самовывоз — улица Георгия Исакова 9',
    deliveryPayment: 'Доставка по городу — 300 р.\n\nОплата при получении',
    dromKind: 'Карданный вал Lexus GX400',
    dromSection: 'Автозапчасти → Трансмиссия',
    applicability: APPLICABILITY,
    characteristics: CHARACTERISTICS,
  };
}

function row(): CatalogRow {
  return {
    id: 7, number: 7, code: 'B-40219', title: 'Фара левая Toyota Camry',
    qualityGrade: 'NO_DEFECTS', condition: 'USED',
    brand: 'Toyota', model: 'Camry', generation: null, yearFrom: null, yearTo: null,
    body: null, engine: null, year: 2007, donorCode: '261',
    price: 27000, installationPrice: null, color: null, description: 'Фара в сборе',
    note: 'скол на креплении',
    manufacturer: 'Toyota', marking: null, section: null, cellCode: 'А-01-1',
    sideLr: 'LEFT', sideFr: 'FRONT',
    qty: 1, oem: null, crosses: null, photoUrl: null, supply: null, equipment: null,
    partName: 'фара', published: true, barcode: null, legacyCode: null, dromAdNumber: null,
    videoUrl: null, textBlock: null, weightKg: null, dimensions: null,
    packageDimensions: null, packageWeightKg: null, createdAt: null, updatedAt: null,
    updatedByName: null, priceChangedAt: null, priceChangedByName: null,
    photoCount: 0, stock: {},
  };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
