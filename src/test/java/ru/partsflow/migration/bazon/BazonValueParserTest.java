package ru.partsflow.migration.bazon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.partsflow.inventory.LateralSide;
import ru.partsflow.inventory.LongitudinalSide;
import ru.partsflow.inventory.QualityGrade;
import ru.partsflow.migration.bazon.BazonValueParser.SupplyKind;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Строки взяты из настоящей выгрузки склада на 35 841 позицию и 440 доноров,
 * включая те, на которых ломается очевидный разбор.
 */
class BazonValueParserTest {

    @Nested
    @DisplayName("Поставка")
    class Supply {

        @Test
        @DisplayName("Контейнер с поставщиком открытым текстом")
        void plainSupplier() {
            var supply = BazonValueParser.parseSupply("Контейнер №17 | 01.07.2026 | Onteco 6");

            assertThat(supply.kind()).isEqualTo(SupplyKind.CONTAINER);
            assertThat(supply.number()).isEqualTo("17");
            assertThat(supply.arrivedOn()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(supply.supplierName()).isEqualTo("Onteco 6");
            assertThat(supply.isStructured()).isTrue();
        }

        @Test
        @DisplayName("Поставщик в скобках — скобки снимаются")
        void supplierInParentheses() {
            assertThat(BazonValueParser.parseSupply("Контейнер №16 | 17.06.2026 | (DDI-22)").supplierName())
                    .isEqualTo("DDI-22");
            assertThat(BazonValueParser.parseSupply("Контейнер №8 | 06.12.2024 | (40FT)").supplierName())
                    .isEqualTo("40FT");
        }

        @Test
        @DisplayName("Поставка вообще без номера и даты не теряется")
        void freeFormSupply() {
            var supply = BazonValueParser.parseSupply("Автозапчасти BMW");

            assertThat(supply.kind()).isEqualTo(SupplyKind.OTHER);
            assertThat(supply.isStructured()).isFalse();
            // На такую поставку ссылаются товары — выбросить её нельзя.
            assertThat(supply.raw()).isEqualTo("Автозапчасти BMW");
        }

        @Test
        @DisplayName("Пустое значение — это отсутствие поставки, а не ошибка")
        void blankSupply() {
            assertThat(BazonValueParser.parseSupply("   ")).isNull();
            assertThat(BazonValueParser.parseSupply(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Номер донора")
    class DonorNumber {

        @Test
        @DisplayName("Номер с кодом лота")
        void withExternalCode() {
            var n = BazonValueParser.parseDonorNumber("418 (1417)");

            assertThat(n.number()).isEqualTo("418");
            assertThat(n.externalCode()).isEqualTo("1417");
            assertThat(n.isParsed()).isTrue();
        }

        @Test
        @DisplayName("Номер без скобок — так записана половина доноров")
        void withoutExternalCode() {
            var n = BazonValueParser.parseDonorNumber("396");

            assertThat(n.number()).isEqualTo("396");
            assertThat(n.externalCode()).isNull();
        }

        @Test
        @DisplayName("Номер не обязан быть числом: машины, купленные не контейнером")
        void nonNumericNumber() {
            var n = BazonValueParser.parseDonorNumber("BMW22");

            assertThat(n.isParsed()).isTrue();
            assertThat(n.number()).isEqualTo("BMW22");
        }
    }

    @Nested
    @DisplayName("Цвет")
    class Color {

        @Test
        void nameAndCode() {
            var c = BazonValueParser.parseColor("Золотистый (4T8)");
            assertThat(c.name()).isEqualTo("Золотистый");
            assertThat(c.code()).isEqualTo("4T8");
        }

        @Test
        @DisplayName("Код цвета бывает и буквенно-цифровым, и длинным")
        void variousCodes() {
            assertThat(BazonValueParser.parseColor("Серебро (NH623M)").code()).isEqualTo("NH623M");
            assertThat(BazonValueParser.parseColor("Panther Black (Metallic)").code()).isEqualTo("Metallic");
        }

        @Test
        void withoutCode() {
            var c = BazonValueParser.parseColor("Серебро");
            assertThat(c.name()).isEqualTo("Серебро");
            assertThat(c.code()).isNull();
        }
    }

    @Nested
    @DisplayName("Числа")
    class Numbers {

        @Test
        @DisplayName("Пробег с пробелом-разделителем разрядов")
        void mileageWithSpaces() {
            assertThat(BazonValueParser.parseInteger("47 064")).isEqualTo(47064);
            assertThat(BazonValueParser.parseInteger("285 596")).isEqualTo(285596);
            assertThat(BazonValueParser.parseInteger("123966")).isEqualTo(123966);
        }

        @Test
        @DisplayName("Неразрывный пробел встречается в выгрузках наравне с обычным")
        void nonBreakingSpace() {
            assertThat(BazonValueParser.parseInteger("47 064")).isEqualTo(47064);
            assertThat(BazonValueParser.parseInteger("47 064")).isEqualTo(47064);
        }

        @Test
        @DisplayName("Точка как разделитель разрядов: 124.000 — это 124 000 км, а не 124")
        void dotThousandsSeparator() {
            assertThat(BazonValueParser.parseInteger("124.000")).isEqualTo(124000);
            assertThat(BazonValueParser.parseInteger("142.000")).isEqualTo(142000);
            assertThat(BazonValueParser.parseInteger("1.234.567")).isEqualTo(1234567);
        }

        @Test
        @DisplayName("Единица измерения дописана в значение")
        void unitSuffix() {
            assertThat(BazonValueParser.parseInteger("100131км")).isEqualTo(100131);
            assertThat(BazonValueParser.parseInteger("52 000 км.")).isEqualTo(52000);
            assertThat(BazonValueParser.parseInteger("31165км")).isEqualTo(31165);
        }

        @Test
        @DisplayName("Мусор не превращается в ноль: ноль — это цена, а не «не смогли разобрать»")
        void garbageIsNullNotZero() {
            assertThat(BazonValueParser.parseInteger("нет данных")).isNull();
            assertThat(BazonValueParser.parseAmount("—")).isNull();
        }

        @Test
        void amounts() {
            assertThat(BazonValueParser.parseAmount("2100")).isEqualByComparingTo("2100");
            assertThat(BazonValueParser.parseAmount("8 574")).isEqualByComparingTo("8574");
            assertThat(BazonValueParser.parseAmount("1234,50")).isEqualByComparingTo("1234.50");
        }
    }

    @Nested
    @DisplayName("Год выпуска, который бывает периодом")
    class Years {

        @Test
        void singleYear() {
            var y = BazonValueParser.parseYearRange("2006");
            assertThat(y.from()).isEqualTo(2006);
            assertThat(y.isSingleYear()).isTrue();
        }

        @Test
        @DisplayName("Диапазон лет — это применимость, схлопывать его нельзя")
        void yearRange() {
            var y = BazonValueParser.parseYearRange("2006-2010");
            assertThat(y.from()).isEqualTo(2006);
            assertThat(y.to()).isEqualTo(2010);
            assertThat(y.isSingleYear()).isFalse();
        }

        @Test
        @DisplayName("Период в форме месяц.год")
        void monthYearRange() {
            var y = BazonValueParser.parseYearRange("10.09-09.15");
            assertThat(y.from()).isEqualTo(2009);
            assertThat(y.to()).isEqualTo(2015);
        }

        @Test
        @DisplayName("Двузначный год трактуется как 20xx: машин 1909 года на разборке нет")
        void twoDigitYear() {
            assertThat(BazonValueParser.parseYearRange("09.15").from()).isEqualTo(2015);
        }

        @Test
        @DisplayName("Тильда — пометка «примерно»; терять из-за неё год глупо")
        void approximateYear() {
            assertThat(BazonValueParser.parseYearRange("2005~").from()).isEqualTo(2005);
            assertThat(BazonValueParser.parseYearRange("2007~").isSingleYear()).isTrue();
        }

        @Test
        @DisplayName("Открытый период «с 2005 и далее»")
        void openEndedRange() {
            var y = BazonValueParser.parseYearRange("2005-");
            assertThat(y.from()).isEqualTo(2005);
            assertThat(y.to()).isNull();
        }

        @Test
        @DisplayName("Битое значение остаётся пустым: догадываться о годе нельзя")
        void garbage() {
            assertThat(BazonValueParser.parseYearRange("н/д")).isNull();
            // «200» — опечатка в исходных данных, а не год.
            assertThat(BazonValueParser.parseYearRange("200")).isNull();
        }
    }

    @Nested
    @DisplayName("Списки")
    class Lists {

        @Test
        @DisplayName("Кросс-номера: разделитель без пробела")
        void crossNumbers() {
            assertThat(BazonValueParser.parseList("4853033281,4853033291,DS2130GS"))
                    .containsExactly("4853033281", "4853033291", "DS2130GS");
        }

        @Test
        @DisplayName("Фото товаров: разделитель с пробелом и хвостовая запятая")
        void photoUrlsWithTrailingComma() {
            String raw = "http://export-content.baz-on.ru/pub/c2226/productphoto/0005/94/0005_94_118.jpg, "
                    + "http://export-content.baz-on.ru/pub/c2226/productphoto/0005/94/0005_94_119.jpg, ";

            assertThat(BazonValueParser.parsePhotoUrls(raw)).hasSize(2);
        }

        @Test
        @DisplayName("Протокол-относительные ссылки доноров достраиваются до https")
        void protocolRelativeUrls() {
            assertThat(BazonValueParser.parsePhotoUrls("//cdn.baz-on.ru/pub/c2226/referencevalueimage/0000/04/0000_04_717.jpg"))
                    .containsExactly("https://cdn.baz-on.ru/pub/c2226/referencevalueimage/0000/04/0000_04_717.jpg");
        }

        @Test
        @DisplayName("Уменьшенная копия заменяется оригиналом")
        void resizedBecomesOriginal() {
            // CDN отдаёт по одному пути и превью, и оригинал: разница
            // в отрезке /rsz/<размер>/. Превью — 49×37, оригинал — 1020×770,
            // замерено. Перенести превью значит навсегда оставить клиента
            // с картинками, по которым деталь не разглядеть.
            assertThat(BazonValueParser.parsePhotoUrls(
                    "https://cdn.baz-on.ru/rsz/thumb/pub/c2226/productphoto/0005/94/0005_94_245.jpg"))
                    .containsExactly(
                    "https://cdn.baz-on.ru/pub/c2226/productphoto/0005/94/0005_94_245.jpg");

            // Ссылка без уменьшения не трогается.
            assertThat(BazonValueParser.parsePhotoUrls(
                    "http://export-content.baz-on.ru/pub/c2226/productphoto/0005/94/0005_94_118.jpg"))
                    .containsExactly(
                    "http://export-content.baz-on.ru/pub/c2226/productphoto/0005/94/0005_94_118.jpg");
        }

        @Test
        void emptyList() {
            assertThat(BazonValueParser.parseList("")).isEmpty();
            assertThat(BazonValueParser.parseList(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Справочные значения")
    class Enums {

        @Test
        void publishFlag() {
            assertThat(BazonValueParser.parsePublishFlag("Да")).isTrue();
            assertThat(BazonValueParser.parsePublishFlag("да")).isTrue();
            assertThat(BazonValueParser.parsePublishFlag("+")).isTrue();
            assertThat(BazonValueParser.parsePublishFlag("Нет")).isFalse();
            assertThat(BazonValueParser.parsePublishFlag("0")).isFalse();

            // Формат колонки не подтверждён на живых данных: её нет в выгрузке
            // по умолчанию. Непонятное значение — null, чтобы вызывающий решал
            // сам, а не получал молчаливое «не публиковать».
            assertThat(BazonValueParser.parsePublishFlag("")).isNull();
            assertThat(BazonValueParser.parsePublishFlag(null)).isNull();
            assertThat(BazonValueParser.parsePublishFlag("возможно")).isNull();
        }

        @Test
        void steering() {
            assertThat(BazonValueParser.parseSteering("Правый руль")).isEqualTo("RIGHT");
            assertThat(BazonValueParser.parseSteering("Левый руль")).isEqualTo("LEFT");
            assertThat(BazonValueParser.parseSteering("")).isNull();
        }

        @Test
        void driveType() {
            assertThat(BazonValueParser.parseDriveType("Передний")).isEqualTo("FWD");
            assertThat(BazonValueParser.parseDriveType("Задний")).isEqualTo("RWD");
            assertThat(BazonValueParser.parseDriveType("Полный")).isEqualTo("AWD");
        }

        @Test
        @DisplayName("«Робот» — это AMT; без него четыре машины не проходят импорт")
        void transmissionIncludingRobot() {
            assertThat(BazonValueParser.parseTransmissionType("МКПП")).isEqualTo("MT");
            assertThat(BazonValueParser.parseTransmissionType("АКПП")).isEqualTo("AT");
            assertThat(BazonValueParser.parseTransmissionType("Вариатор")).isEqualTo("CVT");
            assertThat(BazonValueParser.parseTransmissionType("Робот")).isEqualTo("AMT");
        }

        @Test
        @DisplayName("Сокращения сторон из выгрузки")
        void sides() {
            assertThat(BazonValueParser.parseLateralSide("Лев.")).isEqualTo(LateralSide.LEFT);
            assertThat(BazonValueParser.parseLateralSide("Прав.")).isEqualTo(LateralSide.RIGHT);
            assertThat(BazonValueParser.parseLongitudinalSide("Перед.")).isEqualTo(LongitudinalSide.FRONT);
            assertThat(BazonValueParser.parseLongitudinalSide("Задн.")).isEqualTo(LongitudinalSide.REAR);
        }

        @Test
        @DisplayName("Оценка состояния: все четыре градации")
        void qualityGrades() {
            assertThat(BazonValueParser.parseQualityGrade("Как новая")).isEqualTo(QualityGrade.AS_NEW);
            assertThat(BazonValueParser.parseQualityGrade("Без дефектов")).isEqualTo(QualityGrade.NO_DEFECTS);
            assertThat(BazonValueParser.parseQualityGrade("С дефектами")).isEqualTo(QualityGrade.WITH_DEFECTS);
            assertThat(BazonValueParser.parseQualityGrade("Требует ремонт")).isEqualTo(QualityGrade.NEEDS_REPAIR);
        }

        @Test
        @DisplayName("Ё и регистр не должны ронять разбор: в выгрузке есть «Зелёный» и «Зеленый»")
        void toleratesCaseAndYo() {
            assertThat(BazonValueParser.parseDriveType("ПЕРЕДНИЙ")).isEqualTo("FWD");
            assertThat(BazonValueParser.parseLateralSide("лев.")).isEqualTo(LateralSide.LEFT);
        }

        @Test
        @DisplayName("Незнакомое значение — null, а не выдуманное соответствие")
        void unknownIsNull() {
            assertThat(BazonValueParser.parseTransmissionType("Гидромеханика")).isNull();
            assertThat(BazonValueParser.parseQualityGrade("Отличное")).isNull();
        }
    }

    @Nested
    @DisplayName("Значение по умолчанию для количества")
    class Amounts {

        @Test
        @DisplayName("Ноль остаётся нулём: это «нет на складе», а не «не разобрали»")
        void zeroIsMeaningful() {
            assertThat(BazonValueParser.parseAmount("0")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(BazonValueParser.parseInteger("0")).isZero();
        }
    }
}
