package ru.partsflow.migration.bazon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Итог импорта.
 *
 * <p>Существует ради одного вопроса, который клиент задаёт первым: «а всё ли
 * перенеслось?». Ответить на него «вроде да» нельзя, поэтому считаем каждую
 * сущность и складываем причины отказов с номерами строк — чтобы можно было
 * открыть исходный файл и посмотреть.
 */
public final class ImportReport {

    private final Map<String, Integer> loaded = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();

    /** Список проблем не растёт бесконечно: битый файл не должен съесть память. */
    private static final int MAX_PROBLEMS = 500;

    private int suppressedProblems;

    public void count(String entity) {
        loaded.merge(entity, 1, Integer::sum);
    }

    public void count(String entity, int n) {
        loaded.merge(entity, n, Integer::sum);
    }

    public void problem(long line, String message) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(new Problem(line, message));
        } else {
            suppressedProblems++;
        }
    }

    public int loaded(String entity) {
        return loaded.getOrDefault(entity, 0);
    }

    /**
     * Что и сколько загружено, в порядке появления.
     *
     * <p>Отдаётся целиком, а не по известным заранее именам: клиент спрашивает
     * «а всё ли перенеслось», и ответ на это — весь список, включая строку
     * «товаров пропущено (уже есть)», по которой видно, что запуск повторный.
     */
    public Map<String, Integer> loaded() {
        return Map.copyOf(loaded);
    }

    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    public int problemCount() {
        return problems.size() + suppressedProblems;
    }

    public boolean isClean() {
        return problemCount() == 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Импорт завершён\n");
        loaded.forEach((k, v) -> sb.append("  %-24s %d%n".formatted(k, v)));

        if (problemCount() == 0) {
            sb.append("  проблем нет\n");
            return sb.toString();
        }

        sb.append("  проблем: %d%n".formatted(problemCount()));
        problems.stream().limit(20).forEach(p ->
                sb.append("    строка %d: %s%n".formatted(p.line(), p.message())));
        if (problemCount() > 20) {
            sb.append("    ... и ещё %d%n".formatted(problemCount() - 20));
        }
        return sb.toString();
    }

    public record Problem(long line, String message) {
    }
}
