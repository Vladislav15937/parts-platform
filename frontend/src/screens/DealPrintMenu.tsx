import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import {
  dealPrintDoc,
  documentsOf,
  legalIsEmpty,
  PRINT_FORMS,
} from '../sales/dealPrint';
import type { DealPrintDoc, PrintForm, PrintLine, PrintSeller } from '../sales/dealPrint';
import { useMounted } from '../ui/useMounted';

/**
 * Печать документов сделки: чек, накладная, счёт (задача 0051).
 *
 * <p><b>Зачем.</b> Покупатель б/у запчасти уносит бумагу почти всегда — по ней
 * он через две недели приходит по гарантии, — а печати из сделки не было вовсе.
 * Это каждая продажа: у ориентира за всё время 64 428 выданных сделок, из них
 * больше двадцати за один день.
 *
 * <p><b>Печатает браузер, а не драйвер.</b> Решение по локальному агенту
 * не пересматривается (`docs/bazon-parity.md` §9), и этикетки печатаются так же
 * с самого начала: выбор пункта меню открывает готовый лист и диалог печати,
 * а не скачивает файл.
 *
 * <p><b>`window.print()` зовётся из эффекта, а не из обработчика нажатия</b> —
 * ровно по той же причине, что в `PartLabelPrint`: диалог останавливает
 * страницу, и вызванный до отрисовки, он снял бы изображение с ещё
 * не нарисованного документа. Эффект висит на паре «форма + документ»,
 * поэтому второй выбор в меню (чек после накладной) печатает заново, а не
 * молчит: данные уже загружены, меняется только форма.
 *
 * <p><b>Ключ по сделке обязателен у вызывающего, и он обязан быть своим</b>
 * (`key={`print-${deal.id}`}`, а не голый `deal.id`): карточка живёт дольше
 * одной сделки, и открытый документ предыдущей ушёл бы в печать вместе
 * со следующей — та же ловушка, что у листа этикеток. Но голый `deal.id`
 * здесь уже занят соседом (`ReturnForm`), а двух соседей с одним ключом React
 * дублирует и/или выбрасывает: ломается при этом <b>сосед</b>, не печать.
 * Разобрано в `frontend/CLAUDE.md`.
 */
/**
 * Лист документа — A4 книжной ориентации (задача 0051).
 *
 * <p>Объявлен здесь, а не в `app.css`, по той же причине, что размер этикетки
 * объявлен в `labels/LabelSheet.tsx`: безымянный `@page` — настройка **всего**
 * документа, и один общий на файл стилей означал бы, что чек и рулон этикеток
 * спорят за один размер. Именованные страницы (`@page document` плюс
 * `page: document`) этот спор не решают — замерено настоящим Chrome, он их
 * не поддерживает вовсе и молча возвращается к листу по умолчанию. Подробности
 * замера — в `LabelSheet.tsx`.
 *
 * <p>Ориентация и поля не настраиваются: из набора настроек ориентира
 * «Ориентация печати» не перенесена (решение исполнителя, названо
 * в `sales/CLAUDE.md`), и книжная здесь — то, что стоит у живого клиента.
 */
const PAGE = '@page { size: A4 portrait; margin: 12mm }';

export function DealPrintMenu({ dealId }: { dealId: number }) {
  const [menu, setMenu] = useState(false);
  const [form, setForm] = useState<PrintForm | null>(null);
  // Документ грузится один раз на сделку: четыре формы собираются из одних
  // и тех же данных, и второй запрос за ними ничего бы не уточнил.
  const [doc, setDoc] = useState<DealPrintDoc | null>(null);
  const [error, setError] = useState('');
  // Почему это общий хук, а не ref с эффектом на месте, — в ui/useMounted.ts.
  const mounted = useMounted();

  useEffect(() => {
    if (form !== null && doc !== null) {
      window.print();
    }
  }, [form, doc]);

  if (form !== null && doc !== null) {
    return (
      <div className="card-view__print">
        {/* Размер страницы объявляет сам документ, пока он на экране, —
            почему именно так и почему не `@page document`, разобрано
            в `labels/LabelSheet.tsx`: именованные страницы этот Chrome
            не поддерживает, и замерено это в точках. */}
        <style>{PAGE}</style>
        {/* Предпросмотр он же то, что уйдёт в печать, — как у этикеток:
            отдельная вёрстка для принтера разошлась бы с увиденным,
            и разошлась бы незаметно. */}
        <div className="print-sheet">
          {documentsOf(form).map((kind) => (
            <PrintDocument key={kind} kind={kind} doc={doc} />
          ))}
        </div>
        <p className="note no-print">
          Если диалог печати не открылся — нажмите «Печать». Лист A4, по странице
          на документ.
        </p>
        <div className="filter-row no-print">
          {/* Диалог закрывают и по ошибке, и чтобы поменять принтер:
              повторить надо оттуда же, где документ перед глазами. */}
          <button type="button" onClick={() => window.print()}>
            Печать
          </button>
          <button type="button" className="button--ghost" onClick={() => setForm(null)}>
            Закрыть
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="print-menu">
      <button
        type="button"
        className="button--ghost"
        onClick={() => setMenu(!menu)}
      >
        Печатать
      </button>
      {error !== '' && <p className="note note--error">{error}</p>}
      {menu && (
        <ul className="print-menu__list">
          {PRINT_FORMS.map((option) => (
            <li key={option.key}>
              <button
                type="button"
                className="button--ghost"
                onClick={() => void open(option.key)}
              >
                {option.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );

  async function open(chosen: PrintForm): Promise<void> {
    setError('');
    try {
      const loaded = doc ?? await dealPrintDoc(dealId);
      if (!mounted.current) return;
      setDoc(loaded);
      setForm(chosen);
      setMenu(false);
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof ApiError && cause.message !== ''
          ? cause.message
          : 'Документ не собрался — печатать нечего');
      }
    }
  }
}

/**
 * Один печатный документ.
 *
 * <p>Вёрстка наша: раздел «Печатные формы» у клиента ориентира открыт только
 * на просмотр, и напечатанного листа разведка не видела — выдумывать её
 * задача прямо запретила. Состав полей при этом задан ею дословно.
 */
function PrintDocument({ kind, doc }: {
  kind: 'receipt' | 'waybill' | 'invoice';
  doc: DealPrintDoc;
}) {
  const number = doc.number ?? doc.dealId;
  const title = kind === 'receipt' ? 'Товарный чек'
    : kind === 'waybill' ? 'Товарная накладная'
      : 'Счёт на оплату';

  return (
    <article className="print-doc">
      {kind === 'invoice'
        ? <LegalBlock doc={doc} />
        : <SellerBlock seller={doc.seller} role="Продавец" />}

      <h3 className="print-doc__title">
        {title} № {number} от {day(doc.createdAt)}
      </h3>

      <p className="print-doc__party">
        Покупатель: {buyerTitle(doc)}
        {doc.buyer.phone !== null && doc.buyer.phone !== '' && ` · ${doc.buyer.phone}`}
        {doc.buyer.inn !== null && doc.buyer.inn !== '' && ` · ИНН ${doc.buyer.inn}`}
      </p>

      <Lines lines={doc.lines} />

      <p className="print-doc__total">Итого: {money(doc.total)}</p>

      {/* Оплачено и долг — в чеке: это бумага про деньги, и покупатель уносит
          её вместе с ними. В накладной и в счёте их нет: первая про товар,
          второй выставляется до оплаты. */}
      {kind === 'receipt' && (
        <p className="print-doc__money">
          Оплачено: {money(doc.paid)} · Долг: {money(doc.debt)}
        </p>
      )}

      {/* Пометка об НДС печатается, только если задана: пусто означает
          «не заполнено», а не «НДС 0» (пункт 6 критерия приёмки). */}
      {doc.vatNote !== null && doc.vatNote !== '' && (
        <p className="print-doc__vat">{doc.vatNote}</p>
      )}

      {doc.buyer.note !== null && doc.buyer.note !== '' && (
        <p className="print-doc__note">{doc.buyer.note}</p>
      )}

      {/* Условия возврата и гарантии — в чеке и накладной: в счёт на юр. лицо
          гарантийный текст не идёт, там предмет другой. */}
      {kind !== 'invoice' && doc.extraText !== null && doc.extraText !== '' && (
        <p className="print-doc__extra">{doc.extraText}</p>
      )}

      <Signatures kind={kind} doc={doc} />
    </article>
  );
}

/**
 * Кто продал — блок реквизитов склада выдачи.
 *
 * <p>Причину отсутствия печатает сервер: её читают обе формы, и два написания
 * одного отказа разошлись бы на первой правке. Молчать тут нельзя — продавец
 * отдаёт бумагу покупателю и обязан увидеть, что продавца в ней нет.
 */
function SellerBlock({ seller, role }: { seller: PrintSeller; role: string }) {
  return (
    <div className="print-doc__seller">
      <span className="print-doc__role">{role}</span>
      {seller.details !== null && seller.details !== '' ? (
        <span className="print-doc__details">{seller.details}</span>
      ) : (
        <span className="print-doc__missing">{seller.problem ?? 'Реквизиты не заданы'}</span>
      )}
    </div>
  );
}

/**
 * Реквизиты организации — только в счёте на юр. лицо (пункт 5 критерия
 * приёмки): там плательщик переводит деньги на расчётный счёт, и блок склада
 * этого не несёт.
 */
function LegalBlock({ doc }: { doc: DealPrintDoc }) {
  const legal = doc.legal;
  if (legalIsEmpty(legal)) {
    return (
      <div className="print-doc__seller">
        <span className="print-doc__role">Поставщик</span>
        <span className="print-doc__missing">
          Реквизиты юр. лица не заполнены — задайте их в «Настройках» →
          «Печатные формы». Счёт без ИНН и расчётного счёта покупатель
          не оплатит.
        </span>
      </div>
    );
  }

  return (
    <div className="print-doc__seller">
      <span className="print-doc__role">Поставщик</span>
      <span className="print-doc__details">
        {[
          legal.name,
          legal.address,
          pair('ИНН', legal.inn, 'КПП', legal.kpp),
          pair('Банк', legal.bankName, 'БИК', legal.bankBic),
          pair('Р/с', legal.bankAccount, 'К/с', legal.bankCorrAccount),
        ].filter((line) => line !== null && line !== '').join('\n')}
      </span>
    </div>
  );
}

/** «ИНН 1234 · КПП 5678» — и ни одного пустого ярлыка без значения. */
function pair(
  leftLabel: string, left: string | null, rightLabel: string, right: string | null,
): string {
  return [
    left !== null && left.trim() !== '' ? `${leftLabel} ${left}` : '',
    right !== null && right.trim() !== '' ? `${rightLabel} ${right}` : '',
  ].filter((part) => part !== '').join(' · ');
}

function Lines({ lines }: { lines: PrintLine[] }) {
  return (
    <table className="print-table">
      <thead>
        <tr>
          <th>№</th>
          <th>Наименование</th>
          <th className="num">Кол-во</th>
          <th className="num">Цена</th>
          <th className="num">Сумма</th>
        </tr>
      </thead>
      <tbody>
        {lines.map((line, at) => (
          <tr key={`${line.title}-${at}`}>
            <td>{at + 1}</td>
            <td>{line.title}</td>
            <td className="num">{Number(line.quantity).toLocaleString('ru-RU')}</td>
            <td className="num">{money(line.price)}</td>
            <td className="num">{money(line.amount)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Поля подписей.
 *
 * <p>Стоят после дополнительного текста — так у клиента ориентира («Поле
 * подписи для клиента: После дополнительного текста»). У счёта подписи другие:
 * его подписывают руководитель и главный бухгалтер, и их имена берутся
 * из реквизитов организации.
 */
function Signatures({ kind, doc }: {
  kind: 'receipt' | 'waybill' | 'invoice';
  doc: DealPrintDoc;
}) {
  if (kind === 'invoice') {
    return (
      <div className="print-doc__signs">
        <span>Руководитель ____________________ {doc.legal.director ?? ''}</span>
        <span>
          Главный бухгалтер ____________________ {doc.legal.chiefAccountant ?? ''}
        </span>
      </div>
    );
  }

  return (
    <div className="print-doc__signs">
      {doc.issuerSignature && <span>Товар выдал ____________________</span>}
      {doc.clientSignature && (
        <span>
          {kind === 'waybill'
            ? 'Товар получил ____________________'
            : 'Покупатель ____________________'}
        </span>
      )}
    </div>
  );
}

/**
 * Как назвать покупателя в документе: организацией, если она названа.
 *
 * <p>Юридическое лицо в счёте обязано стоять своим названием — «ООО Ремонт»,
 * а не именем человека, который звонил; у розничной продажи организации нет
 * вовсе, и там имя — «Частное лицо», то же слово, что на всех экранах.
 */
function buyerTitle(doc: DealPrintDoc): string {
  const company = doc.buyer.companyName;
  return company !== null && company.trim() !== '' ? company : doc.buyer.name;
}

function money(value: string): string {
  return `${Number(value).toLocaleString('ru-RU')} ₽`;
}

/** Метка времени — с временем внутри, поэтому `new Date` здесь верен. */
function day(iso: string): string {
  return new Date(iso).toLocaleDateString('ru-RU');
}
