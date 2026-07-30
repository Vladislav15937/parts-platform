package ru.partsflow.publishing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST кабинетов площадок.
 *
 * <p>Только владелец: ключ кабинета — это доступ ко всем объявлениям клиента,
 * и заводить его продавцу незачем.
 *
 * <p>Ключ можно записать и заменить, но не прочитать. Отсутствие метода
 * «показать» здесь — часть защиты, а не недоделка.
 */
@RestController
@RequestMapping("/api/marketplace-accounts")
public class MarketplaceAccountController {

    private final MarketplaceAccountService accounts;

    public MarketplaceAccountController(MarketplaceAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<MarketplaceAccountService.Account> list() {
        return accounts.list();
    }

    @PutMapping("/{id}/credentials")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> setCredentials(@PathVariable Long id,
                                               @Valid @RequestBody CredentialsRequest request) {
        accounts.setCredentials(id, request.secret());
        return ResponseEntity.noContent().build();
    }

    /**
     * Заводит или меняет ссылку на прайс.
     *
     * <p>Возвращает полный путь — его владелец передаёт техспециалисту площадки.
     * Смена ссылки останавливает забор прайса, пока новую не пропишут, поэтому
     * это отдельное действие, а не побочный эффект сохранения настроек.
     */
    @PostMapping("/{id}/feed-url")
    @PreAuthorize("hasRole('OWNER')")
    public FeedUrlView rotateFeedUrl(@PathVariable Long id) {
        accounts.rotateFeedToken(id);
        return new FeedUrlView(feedPathOf(id));
    }

    /** Текущая ссылка на прайс. Пусто — ещё не заводили. */
    @GetMapping("/{id}/feed-url")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public FeedUrlView feedUrl(@PathVariable Long id) {
        return new FeedUrlView(feedPathOf(id));
    }

    private String feedPathOf(Long id) {
        return accounts.feedPath(id).orElse(null);
    }

    public record CredentialsRequest(@NotBlank String secret) {
    }

    public record FeedUrlView(String path) {
    }
}
