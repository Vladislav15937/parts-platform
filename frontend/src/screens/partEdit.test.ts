import { describe, expect, it } from 'vitest';
import { draftOf, dromDateShown, toEdit } from './PartEditForm';
import type { PartEdit } from '../inventory/catalog';

function card(overrides: Partial<PartEdit> = {}): PartEdit {
  return {
    price: 4500, minPrice: 4000, costPrice: 1200, installationPrice: null,
    condition: 'USED',
    qualityGrade: 'NO_DEFECTS', description: null, note: 'скол на креплении',
    textBlock: null, videoUrl: null, marking: null, manufacturer: 'Toyota',
    color: null, section: null, barcode: null,
    weightKg: 3.5, lengthMm: 120, widthMm: 80, heightMm: 45,
    packageLengthMm: null, packageWidthMm: null, packageHeightMm: null,
    packageWeightKg: null, storageCellId: 17, published: true,
    warranty: 'Обговаривается индивидуально',
    pickup: 'Самовывоз — улица Георгия Исакова 9',
    deliveryPayment: 'Доставка по городу — 300 р.\n\nОплата при получении',
    dromKind: 'Карданный вал Lexus GX400',
    dromSection: 'Автозапчасти → Трансмиссия',
    applicability: 'Toyota 4Runner | 5 поколение (2009–2013) | TRN285, GRN285\nДля двигателей | 1GR-FE',
    characteristics: 'Бренд [model]: Zapco\nКоличество каналов [channel]: 4-канальный',
    mayFit: 'Toyota Land Cruiser Prado — KDJ120, KDJ120W\nToyota Land Cruiser Prado — \u041aZJ95',
    adDate: '2026-09-28',
    ...overrides,
  };
}

describe('форма правки карточки', () => {
  it('возвращает прочитанное без изменений', () => {
    // Форма уезжает целиком, поэтому «открыл и сохранил, ничего не трогая»
    // обязано быть тождеством. Иначе каждая правка цены тихо стирает соседнее
    // поле — а замечают это на отчёте через месяц.
    expect(toEdit(draftOf(card()))).toEqual(card());
  });

  it('№ позиции уезжает строкой цифр — большой номер не округляется (задача 0303)', () => {
    // Number('9007199254740993') === 9007199254740992: числом сервер получил бы
    // не тот номер и принял бы его молча. Строкой — ровно набранное, а границу
    // называет сервер словами.
    const draft = { ...draftOf(card()), number: ' 9007199254740993 ' };
    expect(toEdit(draft, ['number']).number).toBe('9007199254740993');
    expect(toEdit(draft, []).number).toBeUndefined();
  });

  it('не теряет себестоимость и минимальную цену', () => {
    // Их нет на витрине: собери форму из строки таблицы — и сохранение
    // стёрло бы закупочную цену, которая снимком уходит в сделку
    // и в отчёт окупаемости.
    const saved = toEdit(draftOf(card({ costPrice: 1200, minPrice: 4000 })));
    expect(saved.costPrice).toBe(1200);
    expect(saved.minPrice).toBe(4000);
  });

  it('не теряет ячейку, которой в форме нет', () => {
    // Адрес полки меняют перемещением, а не карточкой — но и терять его
    // сохранение не должно.
    expect(toEdit(draftOf(card({ storageCellId: 17 }))).storageCellId).toBe(17);
  });

  it('очищенное поле уезжает пустым, а не нулём', () => {
    const draft = { ...draftOf(card()), note: '  ', installationPrice: '' };
    const saved = toEdit(draft);
    expect(saved.note).toBeNull();
    // «Цена установки 0 ₽» в карточке — обещание, которого никто не давал.
    expect(saved.installationPrice).toBeNull();
  });

  it('блоки карточки Дрома уезжают дословно: пустые строки внутри и края не трогаются', () => {
    // Клиент не должен терять слова (задача 0285): ни «пустую строку» между
    // пунктами доставки, ни двойной пробел, ни перевод строки в конце.
    const text = '  Пункт 1\n\n\nПункт  2\n';
    const saved = toEdit({ ...draftOf(card()), deliveryPayment: text });
    expect(saved.deliveryPayment).toBe(text);
    expect(toEdit(draftOf(card())).deliveryPayment).toBe(card().deliveryPayment);
  });

  it('вид детали и раздел как у клиента уезжают дословно и не путаются с «Секцией»', () => {
    // Задача 0286: строка Дрома со стрелкой « → » и краями не трогается; «Секция»
    // склада — другое поле и едет своим ключом.
    const text = ' Автозапчасти → Кузов  ';
    const saved = toEdit({ ...draftOf(card({ section: 'А-01' })), dromSection: text });
    expect(saved.dromSection).toBe(text);
    expect(saved.section).toBe('А-01');
    expect(toEdit(draftOf(card())).dromKind).toBe('Карданный вал Lexus GX400');
    // Очистка — пустая строка, а не null (null сервер читает как «не трогать»).
    expect(toEdit({ ...draftOf(card()), dromKind: '' }).dromKind).toBe('');
    expect(draftOf(card({ dromKind: null })).dromKind).toBe('');
  });

  it('«Подходит» уезжает дословно — с « | », переводами строк и краями, очистка — пустая строка (задача 0287)', () => {
    const text = ' Lexus GX400 | GRJ158 | \nДля двигателей | 1GR-FE  ';
    expect(toEdit({ ...draftOf(card()), applicability: text }).applicability).toBe(text);
    expect(toEdit(draftOf(card())).applicability).toBe(card().applicability);
    expect(toEdit({ ...draftOf(card()), applicability: '' }).applicability).toBe('');
    expect(draftOf(card({ applicability: null })).applicability).toBe('');
  });

  it('«Характеристики» уезжают дословно — с [ключами], переводами строк и краями, очистка — пустая строка (задача 0292)', () => {
    const text = ' model [model]: Zapco\nБренд [model]: Zapco  ';
    expect(toEdit({ ...draftOf(card()), characteristics: text }).characteristics).toBe(text);
    expect(toEdit(draftOf(card())).characteristics).toBe(card().characteristics);
    expect(toEdit({ ...draftOf(card()), characteristics: '' }).characteristics).toBe('');
    expect(draftOf(card({ characteristics: null })).characteristics).toBe('');
  });

  it('«Может подойти» уезжает дословно — с переводами строк и кириллической «К», очистка — пустая строка (задача 0293)', () => {
    const text = ' Toyota Land Cruiser Prado — \u041aZJ95, \u041aZJ90\nLexus GX470 — UZJ120  ';
    expect(toEdit({ ...draftOf(card()), mayFit: text }).mayFit).toBe(text);
    expect(toEdit(draftOf(card())).mayFit).toBe(card().mayFit);
    expect(toEdit({ ...draftOf(card()), mayFit: '' }).mayFit).toBe('');
    expect(draftOf(card({ mayFit: null })).mayFit).toBe('');
  });

  it('«Дата (Дром)» уезжает дословно, очистка — пустая строка, а чужой формат показывается как есть (задача 0298)', () => {
    expect(toEdit(draftOf(card())).adDate).toBe('2026-09-28');
    expect(toEdit({ ...draftOf(card()), adDate: '' }).adDate).toBe('');
    expect(draftOf(card({ adDate: null })).adDate).toBe('');
    expect(dromDateShown('2026-09-28')).toBe('28.09.2026');
    expect(dromDateShown('вчера')).toBe('вчера');
    expect(dromDateShown('')).toBe('');
  });

  it('очищенный блок уезжает пустой строкой, а не null', () => {
    // null сервер читает как «ключа не было» и блок не трогает: вкладка,
    // открытая до выкладки, иначе стирала бы тексты клиента. Очистка — ''.
    const saved = toEdit({ ...draftOf(card()), warranty: '' });
    expect(saved.warranty).toBe('');
    // Блока нет на сервере (null) — форма видит пустую строку, а не «null».
    expect(draftOf(card({ pickup: null })).pickup).toBe('');
  });

  it('принимает запятую как десятичный знак', () => {
    // Так набирают на русской раскладке, и «3,5» не должно превращаться
    // в NaN, то есть в пустой вес.
    expect(toEdit({ ...draftOf(card()), weightKg: '3.5' }).weightKg).toBe(3.5);
  });

  it('нечисловое значение не уезжает на сервер числом', () => {
    expect(toEdit({ ...draftOf(card()), price: 'дорого' }).price).toBeNull();
  });

  it('состояние уезжает кодом и переживает открытие без правки', () => {
    // Состояние правится с задачи 0039, и «открыл и сохранил, ничего
    // не трогая» обязано остаться тождеством и для него: уехавшее пустым,
    // оно у менеджера отобрало бы правку карточки целиком (сервер
    // сравнивает присланное с текущим).
    expect(toEdit(draftOf(card({ condition: 'CONTRACT' }))).condition).toBe('CONTRACT');
  });

  it('пустое состояние — «не трогать», а не «очистить»', () => {
    // У детали не бывает состояния «не заполнено»: колонка NOT NULL
    // с умолчанием. Пустое поле формы поэтому означает «оставить как было»,
    // и это единственное поле формы с такой семантикой.
    expect(toEdit({ ...draftOf(card()), condition: '' }).condition).toBeNull();
  });
});
