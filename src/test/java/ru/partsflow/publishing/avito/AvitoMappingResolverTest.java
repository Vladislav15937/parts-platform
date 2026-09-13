package ru.partsflow.publishing.avito;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Позиция без категории уезжает в общую категорию, а не роняет выгрузку.
 *
 * <p>Сторож на мёртвую проверку, а не на возможность Авито: сама выгрузка
 * на площадку отложена решением владельца продукта, и эта правка её
 * не приближает — она чинит написанное обещание, которое не выполнялось.
 *
 * <p>{@code Mapping.fallback()} заведён ровно затем, чтобы «одна незамапленная
 * позиция не должна валить выгрузку всего склада» (его javadoc). Но до него
 * не доходило управление: {@code ConcurrentHashMap.computeIfAbsent}
 * на {@code null}-ключе бросает {@code NullPointerException} <b>раньше</b>,
 * чем зовёт функцию загрузки, — то есть защита молчала как раз на позиции
 * без категории. А позиция без категории здесь нормальна: категорию даёт
 * эталонный вид детали, и пока наименование не сопоставлено со справочником,
 * её нет вовсе ({@code intake/CLAUDE.md}).
 *
 * <p><b>Откат:</b> убрать проверку {@code categoryId == null} в
 * {@code AvitoMappingResolver.resolve} — тест падает с
 * {@code NullPointerException}, а не с отказом проверки.
 *
 * <p>Зовётся служба напрямую, минуя {@code AvitoFeedWriter}: единственный
 * существующий тест выгрузки подменяет резолвер заглушкой и строит позицию
 * с заполненной категорией, то есть настоящий {@code resolve(null)}
 * не проверял никто.
 */
class AvitoMappingResolverTest {

    /**
     * Хранилище не нужно и не должно понадобиться: до похода в базу
     * этот вызов не доходит вовсе. Поэтому {@code null} вместо
     * {@code JdbcTemplate} здесь не небрежность, а вторая половина
     * утверждения — позиция без категории не стоит ни одного запроса.
     */
    private final AvitoMappingResolver resolver = new AvitoMappingResolver(null);

    @Test
    @DisplayName("Позиция без категории получает общую категорию, а не NPE")
    void missingCategoryFallsBackInsteadOfThrowing() {
        AvitoMappingResolver.Mapping mapping = resolver.resolve(null);

        assertThat(mapping).isEqualTo(AvitoMappingResolver.Mapping.fallback());
        assertThat(mapping.category()).isEqualTo("Запчасти и аксессуары");
        assertThat(mapping.goodsType()).isEqualTo("Запчасти");
    }

    // Повтор идёт тем же путём: пустой ключ в кэш не попадает и не мешает
    // соседям — иначе первая же незамапленная позиция отравила бы кэш.
    @Test
    @DisplayName("Повторный запрос без категории отвечает так же")
    void missingCategoryIsStable() {
        assertThat(resolver.resolve(null)).isEqualTo(resolver.resolve(null));
    }
}
