package ru.partsflow.sales;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Каждое состояние сделки названо словом на всех трёх поверхностях.
 *
 * <p><b>Почему проверка стоит здесь, а не во фронтенде.</b> Источник правды —
 * само перечисление, и здесь оно доступно как `values()`, а не как разбор
 * текста регулярным выражением. Задача 0048 требовала проверять «по самому
 * перечислению, а не по списку в тесте» — сильнее `@EnumSource` этого
 * не сделать ничем: новая константа входит в перебор в тот же момент,
 * когда её дописали в enum, и молча пропустить её нельзя.
 *
 * <p><b>Чего стоила пропущенная строка.</b> `RETURNED` не было в словаре
 * экрана «Клиенты», и в колонке состояния у возвращённой сделки стояло
 * слово `RETURNED` — внутреннее представление на экране у человека. Читать
 * его никто не умеет, а спросить не у кого: выглядит как поломка.
 * У позиции сделки та же дыра была на `DRAFT` — продавец, открывший
 * необеспеченный заказ с площадки, видел в строке товара «draft».
 *
 * <p><b>Тест читает файл, а не собранный фронтенд.</b> Собирать ради этого
 * бандл дороже правки, а словарь — это объектный литерал с ключами:
 * если ключа в файле нет, слова нет и на экране. Обратное («ключ есть,
 * а на экран не доехал») стережёт `dealStatus.test.ts` рядом с самим кодом.
 */
class DealStatusVocabularyTest {

    private static final Path VOCABULARY = Path.of("frontend/src/sales/dealStatus.ts");

    /** Словарь целиком: от `export const ИМЯ … = {` до закрывающей скобки. */
    private static String dictionary(String name) {
        String source;
        try {
            source = Files.readString(VOCABULARY, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(
                    "не открылся " + VOCABULARY.toAbsolutePath()
                            + " — тест запускают из корня репозитория", cause);
        }
        Matcher found = Pattern
                .compile("export const " + name + "\\b[^{]*\\{(.*?)\\n\\};", Pattern.DOTALL)
                .matcher(source);
        assertThat(found.find())
                .withFailMessage("в %s не нашлось словаря %s — его переименовали"
                        + " или переложили, а проверка полноты осталась здесь", VOCABULARY, name)
                .isTrue();
        return found.group(1);
    }

    private static final String DEAL = dictionary("DEAL_STATUS_NAMES");
    private static final String SHARED = dictionary("SHARED_DEAL_STATUS_NAMES");
    private static final String ITEM = dictionary("DEAL_ITEM_STATUS_NAMES");

    /** Ключ со значением-строкой: `RESERVED: 'Отложена',`. */
    private static boolean names(String dictionary, Enum<?> status) {
        return Pattern.compile("(?m)^\\s*" + status.name() + "\\s*:\\s*['\"].+['\"]")
                .matcher(dictionary)
                .find();
    }

    @ParameterizedTest
    @EnumSource(DealStatus.class)
    @DisplayName("состояние сделки названо словом в кабинете")
    void dealStatusIsNamed(DealStatus status) {
        assertThat(names(DEAL, status))
                .withFailMessage("состояние %s есть в DealStatus, но слова для него нет"
                        + " в DEAL_STATUS_NAMES (%s): на экране сделки встанет сырой код."
                        + " Так уже было с RETURNED в «Клиентах».", status, VOCABULARY)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(DealStatus.class)
    @DisplayName("состояние сделки названо словом на странице покупателя")
    void sharedDealStatusIsNamed(DealStatus status) {
        assertThat(names(SHARED, status))
                .withFailMessage("состояние %s есть в DealStatus, но слова для него нет"
                        + " в SHARED_DEAL_STATUS_NAMES (%s): сырой код увидит покупатель,"
                        + " открывший ссылку от продавца.", status, VOCABULARY)
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(DealItemStatus.class)
    @DisplayName("состояние позиции названо словом")
    void dealItemStatusIsNamed(DealItemStatus status) {
        assertThat(names(ITEM, status))
                .withFailMessage("состояние %s есть в DealItemStatus, но слова для него нет"
                        + " в DEAL_ITEM_STATUS_NAMES (%s): в строке товара встанет сырой код."
                        + " Так уже было с DRAFT у заказа с площадки.", status, VOCABULARY)
                .isTrue();
    }

    @Test
    @DisplayName("разбор словаря не выродился в пустую строку")
    void dictionariesAreNotEmpty() {
        // Иначе проверки выше зеленели бы на любом файле: пустой словарь
        // не содержит ни одного состояния, но и `find()` по нему не падает —
        // падает только утверждение, которого никто не написал.
        assertThat(DEAL.lines().count())
                .withFailMessage("DEAL_STATUS_NAMES разобран пустым — сломан разбор,"
                        + " а не словарь")
                .isGreaterThan(1);
        assertThat(SHARED.lines().count()).isGreaterThan(1);
        assertThat(ITEM.lines().count()).isGreaterThan(1);
    }
}
