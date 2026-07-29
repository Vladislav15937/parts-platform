package ru.partsflow.publishing;

import ru.partsflow.inventory.Part;

import java.util.List;

/**
 * Адаптер площадки.
 *
 * <p>Площадки работают по разным моделям, и интерфейс это учитывает:
 * Авито <b>забирает</b> фид сам по постоянному URL (pull), Дром принимает
 * <b>отправленный</b> прайс (push). Общее у них — набор объявлений на входе
 * и статусы модерации на выходе.
 *
 * <p>Auto.ru здесь нет намеренно: раздел запчастей закрыт с февраля 2022 года.
 */
public interface MarketplaceAdapter {

    Marketplace code();

    /**
     * Публикует текущий набор объявлений арендатора.
     * Для pull-модели — пересобирает и выкладывает фид; для push — отправляет прайс.
     */
    PublishResult publish(long accountId, List<Part> parts);

    /**
     * Забирает статусы модерации. Без этого клиент не понимает, почему
     * объявления не видны, и идёт в поддержку — это половина обращений.
     */
    List<ListingStatus> pullStatuses(long accountId);

    enum Marketplace {
        AVITO, DROM, JAPANCAR
    }

    record PublishResult(boolean success, int itemCount, String feedRef, String error) {

        public static PublishResult ok(int itemCount, String feedRef) {
            return new PublishResult(true, itemCount, feedRef, null);
        }

        public static PublishResult failed(String error) {
            return new PublishResult(false, 0, null, error);
        }
    }

    record ListingStatus(long partId, String externalId, String status, String errorText) {
    }
}
