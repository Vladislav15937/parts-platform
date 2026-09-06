package ru.partsflow.inventory;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Инвентаризация склада.
 *
 * <p><b>Почему это не складской документ, а отдельная сущность.</b> Документ
 * приёмки или списания знает, что и сколько двигать. Инвентаризация не знает:
 * она сравнивает факт с учётом, и движения появляются только на расхождениях.
 * Смысл операции — не изменить остаток, а узнать, сошёлся ли он.
 *
 * <p><b>Учётный остаток фиксируется снимком при открытии</b>
 * ({@code inventory_line.qty_expected}). Читать его при проведении нельзя:
 * между открытием и проведением проходят часы, склад продолжает работать,
 * и каждая продажа превратилась бы в недостачу.
 *
 * <p>Пересчёт не останавливает продажи. На разборке склад работает всегда,
 * и «заморозить на день» — не вариант. Поэтому у каждой строки есть момент
 * подсчёта, а расхождение считается против учётного остатка <b>на этот
 * момент</b>, а не на открытие сессии.
 */
@Entity
@Table(name = "inventory_session")
public class InventorySession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warehouse_id", nullable = false)
    private Long warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.OPEN;

    @Column(name = "started_by")
    private Long startedBy;

    /**
     * Момент открытия ставит БД. {@code @Generated} обязателен: по этому
     * времени считается окно журнала при расчёте расхождений, и null здесь
     * ломает всю инвентаризацию, а не только отображение.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "started_at", insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /**
     * Комментарий человека к пересчёту: «83619 не найден», «Не сканировали».
     *
     * <p>Ради него в журнал пересчётов и заходят: номер и дата говорят,
     * что документ был, а зачем его открывали и чем кончилось — только он.
     *
     * <p>Пусто — это {@code null}, а не пустая строка. Разница не
     * косметическая: «не заполнено» и «значение» в этом проекте уже
     * расходились дважды — на снятом штрихкоде и на нулевой цене установки, —
     * и оба раза пустая строка выдавала себя за ответ человека.
     */
    @Column(name = "note")
    private String note;

    /** {@code nullable = false} — иначе Hibernate вставит строку без ссылки на сессию. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "session_id", nullable = false)
    private List<InventoryLine> lines = new ArrayList<>();

    protected InventorySession() {
    }

    public InventorySession(Long warehouseId, Long startedBy) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("Инвентаризация проводится по складу");
        }
        this.warehouseId = warehouseId;
        this.startedBy = startedBy;
    }

    // ---------- поведение ----------

    InventoryLine addLine(Long partId, java.math.BigDecimal qtyExpected, Long cellId) {
        requireOpen("добавить позицию");

        InventoryLine line = new InventoryLine(partId, qtyExpected, cellId);
        lines.add(line);
        return line;
    }

    /**
     * Пересчёт закончен, можно смотреть расхождения и проводить.
     *
     * <p>Непосчитанные строки допустимы: инвентаризацию делают по секциям,
     * и «до этой полки не дошли» — обычное дело. Такие строки в корректировку
     * не попадают: не найдено и не посчитано — разные вещи, и подменять одно
     * другим значит списать полсклада.
     */
    public void finishCounting() {
        requireOpen("завершить пересчёт");
        this.status = SessionStatus.COUNTED;
    }

    /**
     * Закрывает пересчёт — но только когда проведены все посчитанные строки.
     *
     * <p>Строка, которую нельзя провести (недостача по детали, обещанной
     * покупателю), оставляет сессию в состоянии «посчитана»: продавец снимет
     * резерв, и проведение допишет остаток. Закрыв сессию раньше, мы отняли бы
     * у кладовщика возможность довести пересчёт до конца — а расхождение
     * никуда не делось бы.
     */
    public void apply(Instant when) {
        if (status != SessionStatus.COUNTED) {
            throw new IllegalStateException(
                    "Проводят завершённый пересчёт, а сессия в состоянии " + status);
        }
        if (hasUnapplied()) {
            return;
        }
        this.status = SessionStatus.APPLIED;
        this.appliedAt = when;
    }

    /** Есть ли посчитанные строки, которые ещё не проведены. */
    public boolean hasUnapplied() {
        return countedLines().stream().anyMatch(line -> !line.isApplied());
    }

    /** Отменяет сессию. Проведённую отменить нельзя — журнал неизменяем. */
    public void cancel() {
        if (status == SessionStatus.APPLIED) {
            throw new IllegalStateException(
                    "Проведённую инвентаризацию не отменяют: корректировки уже в журнале. "
                            + "Оформи встречную");
        }
        this.status = SessionStatus.CANCELLED;
    }

    /**
     * Пишет комментарий, пока пересчёт живой.
     *
     * <p>Закрытый документ не комментируют: проведение записало корректировки
     * в журнал движений, отмена выбросила лист обхода, и приписка задним
     * числом объясняла бы уже случившееся не тем, что видел писавший.
     *
     * <p>Отказ — {@link IllegalStateException}, то есть 409 со словами,
     * а не пятисотка: пишут комментарий с телефона, а офлайн-очередь
     * повторяет 5xx вечно.
     *
     * <p>Пустой текст стирает комментарий в {@code null}, а не кладёт пустую
     * строку: «не заполнено» обязано отличаться от «человек написал пусто».
     */
    public void changeNote(String text) {
        if (status == SessionStatus.APPLIED || status == SessionStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Комментарий пишут, пока пересчёт не проведён и не отменён");
        }
        this.note = text == null || text.isBlank() ? null : text.strip();
    }

    public boolean isOpen() {
        return status == SessionStatus.OPEN;
    }

    /** Посчитанные строки: только они дают корректировку. */
    public List<InventoryLine> countedLines() {
        return lines.stream().filter(InventoryLine::isCounted).toList();
    }

    private void requireOpen(String action) {
        if (status != SessionStatus.OPEN) {
            throw new IllegalStateException(
                    "Нельзя %s: сессия в состоянии %s".formatted(action, status));
        }
    }

    // ---------- доступ ----------

    public Long getId() {
        return id;
    }

    public Long getWarehouseId() {
        return warehouseId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Long getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    /** {@code null} — комментария нет; пустой строки тут не бывает. */
    public String getNote() {
        return note;
    }

    public List<InventoryLine> getLines() {
        return List.copyOf(lines);
    }

    public enum SessionStatus {

        /** Идёт пересчёт. Склад при этом продолжает работать. */
        OPEN,

        /** Пересчёт закончен, расхождения видны, корректировки ещё не в журнале. */
        COUNTED,

        /** Корректировки записаны в журнал движений. */
        APPLIED,

        CANCELLED
    }
}
