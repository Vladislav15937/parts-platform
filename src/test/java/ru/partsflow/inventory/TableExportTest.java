package ru.partsflow.inventory;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Право «скачать таблицу» записано одним местом, и экран с сервером
 * о нём согласны (задача 0175).
 *
 * <p><b>Как выглядело.</b> Одно и то же право стояло в двух соседних
 * контроллерах двумя способами — константой {@code EXPORTS} у колёс
 * и литералом у витрины склада. Совпадали они по значению, но переезжать
 * при замене ролей полномочиями (задача 0044) им предстояло порознь, и второе
 * место осталось бы с прежними ролями молча: кладовщик однажды скачал бы
 * один файл из двух. А сверка экрана с сервером, на которую ссылался
 * {@code exportRole.test.tsx}, не существовала вовсе: и он, и
 * {@code ExportRoleTest} перечисляют роли своими руками, каждый у себя.
 *
 * <p><b>Почему перебор, а не проверка двух названных методов.</b> Третья
 * выгрузка таблицей приедет новым контроллером, а не правкой этих двух,
 * и проверка по списку её не увидит. Поэтому выгрузкой здесь считается
 * признак, а не имя пути: GET, который ничего не возвращает и пишет
 * в {@link HttpServletResponse} сам, — так в проекте устроены все файлы,
 * отдаваемые потоком. Каждая такая точка обязана либо стоять под
 * {@link TableExport#ALLOWED}, либо быть названа в {@link #NOT_A_TABLE_EXPORT}
 * с причиной.
 *
 * <p>Сравнение идёт по значению аннотации — отличить константу от литерала
 * с тем же текстом отражение не может. Но этого и хватает: литерал, равный
 * сегодняшнему {@code ALLOWED}, краснеет здесь в тот день, когда
 * {@code ALLOWED} изменят, то есть разойтись с правом молча не может.
 */
class TableExportTest {

    private static final Path TABS = Path.of("frontend/src/screens/tabs.ts");

    /**
     * Потоковые точки, которые выгрузкой таблицы не являются. Ключ — класс
     * целиком или {@code Класс#метод}. Пометка без причины не принимается:
     * исключение без довода — это просто выключенная проверка.
     */
    private static final Map<String, String> NOT_A_TABLE_EXPORT = Map.of(
            "DromFeedController",
            "прайс и снимки забирает площадка без входа; доступ даёт секрет в адресе, а не роль",
            "PhotoController#archive",
            "в архиве ровно те снимки, что открывший карточку и так видит; роль не проверяется "
                    + "намеренно (inventory/CLAUDE.md, «Роль у архива не проверяется»)",
            "ReportController#soldItemsExport",
            "файл того же отчёта, что на экране, под правом отчётов (класс ReportController), "
                    + "и кнопка на экране — по праву отчётов, а не по EXPORT_ROLES; станет ли он "
                    + "полномочием «скачивать таблицу» — вопрос задачи 0044 и владельца продукта");

    @Test
    @DisplayName("Каждая выгрузка таблицей стоит под одним правом")
    void everyTableExportUsesTheOneRight() throws Exception {
        Map<String, String> streams = streamingEndpoints();

        assertThat(streams.keySet())
                .as("перебор не нашёл выгрузок склада и колёс — признак выгрузки перестал работать, "
                        + "и проверка ниже прошла бы на пустом")
                .contains("CatalogController#export", "WheelController#export");

        for (Map.Entry<String, String> stream : streams.entrySet()) {
            String key = stream.getKey();
            if (isExcepted(key)) {
                continue;
            }
            assertThat(stream.getValue())
                    .as("%s отдаёт файл потоком, но не стоит под TableExport.ALLOWED: "
                            + "право «скачать таблицу» записано в двух местах. Сошлитесь на "
                            + "TableExport.ALLOWED либо назовите точку в NOT_A_TABLE_EXPORT с причиной",
                            key)
                    .isEqualTo(TableExport.ALLOWED);
        }
    }

    /**
     * Исключение, которому больше нечего исключать, снимается: иначе через
     * месяц список читают как разрешение, а не как перечень с причинами.
     */
    @Test
    @DisplayName("Каждое исключение указывает на живую точку и называет причину")
    void exceptionsAreAlive() throws Exception {
        Set<String> streams = streamingEndpoints().keySet();

        for (Map.Entry<String, String> exception : NOT_A_TABLE_EXPORT.entrySet()) {
            assertThat(exception.getValue())
                    .as("исключение %s без причины", exception.getKey())
                    .isNotBlank();
            assertThat(streams)
                    .as("исключение %s ни на что не указывает — снимите его", exception.getKey())
                    .anyMatch(key -> key.equals(exception.getKey())
                            || key.startsWith(exception.getKey() + "#"));
        }
    }

    /**
     * Экран и сервер решают одно и то же. {@code EXPORT_ROLES} прячет кнопку,
     * {@code @PreAuthorize} закрывает адрес, и разъехавшись, они дают либо
     * видимую кнопку с отказом в ответ, либо закрытую кнопку у того, кому
     * файл положен. Тест на стороне сервера, а не фронтенда, по той же
     * причине, что {@code WordingConsistencyTest}: чтение файла в vitest
     * требует типов Node, которых в сборке нет.
     */
    @Test
    @DisplayName("EXPORT_ROLES на экране — те же роли, что в праве сервера")
    void screenAndServerAgree() throws IOException {
        Matcher screen = Pattern.compile("export const EXPORT_ROLES = \\[([^\\]]*)\\]")
                .matcher(Files.readString(TABS));
        assertThat(screen.find())
                .as("в %s нет объявления EXPORT_ROLES — экрану нечем прятать кнопку", TABS)
                .isTrue();

        assertThat(quoted(screen.group(1)))
                .as("кнопку «Скачать таблицу» экран показывает не тем, кому сервер отдаёт файл "
                        + "(EXPORT_ROLES против TableExport.ALLOWED)")
                .isEqualTo(quoted(TableExport.ALLOWED));
    }

    /** Ключ {@code Класс#метод} → выражение {@code @PreAuthorize} (метод, иначе класс; пусто — нет). */
    private static Map<String, String> streamingEndpoints() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Map<String, String> found = new LinkedHashMap<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("ru.partsflow")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            for (Method method : type.getDeclaredMethods()) {
                if (!isStreamingGet(method)) {
                    continue;
                }
                PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                PreAuthorize effective = onMethod != null ? onMethod : onClass;
                found.put(type.getSimpleName() + "#" + method.getName(),
                        effective == null ? "" : effective.value());
            }
        }
        return found;
    }

    private static boolean isStreamingGet(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null || method.getReturnType() != void.class) {
            return false;
        }
        boolean get = mapping.method().length == 0
                || Arrays.asList(mapping.method()).contains(RequestMethod.GET);
        return get && Arrays.asList(method.getParameterTypes()).contains(HttpServletResponse.class);
    }

    private static boolean isExcepted(String key) {
        return NOT_A_TABLE_EXPORT.containsKey(key)
                || NOT_A_TABLE_EXPORT.containsKey(key.substring(0, key.indexOf('#')));
    }

    /** Роли в кавычках, без порядка: {@code ['OWNER', 'MANAGER']} и {@code hasAnyRole('OWNER','MANAGER')}. */
    private static Set<String> quoted(String text) {
        Set<String> roles = new TreeSet<>();
        Matcher role = Pattern.compile("'([A-Z_]+)'").matcher(text);
        while (role.find()) {
            roles.add(role.group(1));
        }
        return roles;
    }
}
