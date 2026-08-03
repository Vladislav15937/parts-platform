package ru.partsflow.sales;

import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Движение по лицевому счёту — журнал, и он неизменяем.
 *
 * <p>До 4 августа 2026 правку и удаление отбивал триггер базы. Правило
 * «логика только в приложении» его сняло, а вместе с ним и защиту от прямого
 * SQL: приложение ходит в базу суперпользователем, и права его не
 * ограничивают. Значит гарантия теперь держится кодом, и держать её надо там,
 * где её нельзя обойти по невнимательности.
 *
 * <p>Отсюда {@code Repository}, а не {@code JpaRepository}: методов правки
 * и удаления в этом интерфейсе нет вовсе — ни {@code delete}, ни
 * {@code deleteAll}, ни {@code saveAll}. Написать их не получится, компилятор
 * не даст. А {@code @Immutable} на сущности говорит Hibernate не отправлять
 * UPDATE даже если поле изменят в памяти.
 *
 * <p>Исправление ошибки — встречной записью, как и было.
 */
public interface CustomerAccountEntryRepository extends Repository<CustomerAccountEntry, Long> {

    List<CustomerAccountEntry> findByCustomerIdOrderByIdDesc(Long customerId);

    CustomerAccountEntry save(CustomerAccountEntry entry);
}
