package ru.partsflow.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import ru.partsflow.platform.admin.ServiceAdminAuthenticationProvider;
import ru.partsflow.platform.admin.ServiceAdminPrincipal;
import ru.partsflow.platform.session.LogoutRecorder;

import java.util.List;

/**
 * Аутентификация: сессия в cookie.
 *
 * <p>Токены появятся с нативным клиентом; до тех пор сессия проще и не требует
 * ротации. PWA живёт на том же домене, поэтому cookie ей подходит.
 *
 * <p><b>Ответ на неаутентифицированный запрос к API — 401, а не редирект
 * на форму.</b> Клиент здесь один и тот же для человека и для очереди
 * офлайн-отправки: редирект она разберёт как успех и удалит запись из очереди,
 * потеряв работу приёмщика. Перенаправляется только навигация браузера
 * на страницу вне {@code /api} и {@code /actuator} — {@link SignInRedirect}.
 */
@Configuration
@EnableWebSecurity
// Проверки роли стоят на методах (@PreAuthorize), а не путями в этом файле:
// правило «создавать сотрудников может владелец» читается там, где создают
// сотрудника, а не в настройках, куда никто не смотрит.
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AuthenticationManager authenticationManager,
                                           LogoutRecorder logoutRecorder)
            throws Exception {

        // CSRF-токен в cookie, читаемой скриптом: PWA обязана положить его
        // в заголовок сама. Отключать CSRF при сессии в cookie нельзя — это
        // и есть тот случай, для которого он придуман.
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();

        http
                .csrf(c -> c.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Создание арендатора — единственное исключение.
                        // CSRF защищает от подделки запроса с чужими cookie,
                        // а здесь cookie не участвуют вовсе: запрос авторизуется
                        // секретом в теле. Кто секрет знает — вызовет напрямую,
                        // кто не знает — подделкой ничего не добьётся.
                        //
                        // Вход сюда не входит: там появляется сессия, и подделка
                        // входа (жертву логинят в чужой аккаунт) — реальная атака.
                        //
                        // Регистрация компании клиентом (задача 0184) — второе:
                        // запрос приходит от того, у кого сессии ещё нет и
                        // которого мы ещё не знаем, cookie он не использует и
                        // ничего не делает от имени вошедшего. Подделывать
                        // браузером нечего: то же самое можно прислать напрямую.
                        .ignoringRequestMatchers("/api/provisioning/**", "/api/mail/events/**",
                                "/api/registration/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authenticationManager(authenticationManager)
                // Программе — 401, человеку на странице — перенаправление на вход
                // (задача 0265). Почему граница проходит по /api — SignInRedirect.
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(SignInRedirect.TO_LOGIN,
                                SignInRedirect.PAGE_NAVIGATION)
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                AnyRequestMatcher.INSTANCE))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                        // Вход в систему администрирования сервиса (задача 0042) —
                        // за ним тоже ещё нет никого. CSRF при этом действует,
                        // как у входа в кабинет: подделка входа реальна и здесь.
                        .requestMatchers("/api/admin/login").permitAll()
                        // Арендатора создавать некому: его самого ещё нет,
                        // а значит нет и учётных записей, под которыми входят.
                        // Механизм защищён секретом из конфигурации, пустой
                        // секрет его выключает — см. ProvisioningController.
                        .requestMatchers("/api/provisioning/**").permitAll()
                        // Регистрация компании клиентом, задача 0184: входить
                        // некому, компании ещё нет. Защищена не секретом, а
                        // вычислительной преградой, пределом с адреса и
                        // потолком числа компаний — см. RegistrationController.
                        // Секрет управляющего контура она не использует и
                        // не обходит: это другой путь, а не другая дверь в тот же.
                        .requestMatchers("/api/registration/**", "/register").permitAll()
                        // Страница входа (задача 0265): за ней ещё нет никого,
                        // как и за корнем ниже.
                        .requestMatchers(HttpMethod.GET, "/login").permitAll()
                        // Страница системы администрирования — тот же случай:
                        // её вход живёт на ней самой.
                        .requestMatchers(HttpMethod.GET, "/admin").permitAll()
                        // Отчёты о доставке писем присылает оператор: сессии у
                        // него нет и CSRF-токена тоже. Права даёт секрет в адресе,
                        // см. MailEventController.
                        .requestMatchers("/api/mail/events/**").permitAll()
                        // Проверки живости нужны балансировщику до всякого входа.
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Готовность спрашивает docker и шаг выкладки, а у них
                        // нет и не может быть учётной записи сотрудника —
                        // то же соображение, что у метрик ниже. Закрыт адрес
                        // теми же двумя способами: порт приложения не
                        // опубликован, а терминатор отдаёт на /actuator 404.
                        .requestMatchers("/actuator/readiness").permitAll()
                        // Включён ли управляющий контур — тот же случай:
                        // спрашивают оператор со скриптом и шаг выкладки,
                        // учётной записи у них нет. Секрет наружу не идёт
                        // ни в каком виде, ответ — «да» или «нет»
                        // (ProvisioningStateEndpoint).
                        .requestMatchers("/actuator/provisioning").permitAll()
                        // Метрики забирает сборщик — процесс, у которого нет
                        // и не может быть учётной записи сотрудника. Наружу
                        // адрес при этом не смотрит: порт приложения не
                        // опубликован, а терминатор отдаёт на /actuator 404.
                        // Оба ограничения нужны вместе — открытые метрики
                        // рассказывают об устройстве системы и о нагрузке
                        // больше, чем стоит рассказывать снаружи.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Прайс забирает сервер Дрома: cookie у него нет
                        // и не будет. Права даёт секрет в самом адресе,
                        // см. DromFeedController.
                        .requestMatchers("/feeds/**").permitAll()
                        // Сделка по ссылке: у покупателя нет учётной записи
                        // и не будет. Права даёт секрет в адресе, ссылка живёт
                        // две недели, а показывает только его собственную
                        // покупку — см. SharedDealController.
                        .requestMatchers("/api/shared/**", "/s/**").permitAll()
                        // Сама PWA. В бою она отдаётся этим же приложением —
                        // фронтенд и API обязаны жить на одном домене, иначе
                        // не работают ни сессия в cookie, ни чтение CSRF-токена
                        // скриптом. Закрывать её аутентификацией бессмысленно:
                        // это экран входа, за ним ещё нет никого.
                        .requestMatchers("/", "/index.html", "/assets/**",
                                "/sw.js", "/manifest.webmanifest", "/favicon.ico")
                        .permitAll()
                        // Выйти может кто угодно вошедший, включая «Просмотр»:
                        // запрет ниже смотрит на метод, а выход — это POST.
                        .requestMatchers("/api/auth/logout").authenticated()
                        // Настройки своего экрана — тоже: запрет ниже про то,
                        // что «Просмотр» не меняет данные предприятия, а здесь
                        // он меняет только то, как склад разложен у него самого
                        // на экране. Без этого исключения сортировка, отборы
                        // и состав колонок не сохранялись бы ровно у тех двух
                        // ролей, которые на витрину и смотрят («Просмотр»
                        // и «Ревизор»), — причём молча, отказом на каждое
                        // нажатие. Адрес назван точно, а не шаблоном на /api/**:
                        // исключение из правила должно оставаться размером
                        // в одно место.
                        .requestMatchers(HttpMethod.PUT, "/api/me/settings/**").access(TENANT_MEMBER)
                        // Граница между системой администрирования и кабинетами
                        // (задача 0042, решение владельца продукта от 8 сентября
                        // 2026: «отдельная система администрирования, живущая
                        // отдельно от всех разборок»). Правила стоят выше
                        // запретов по методам: первое совпадение решает.
                        //
                        // «Кто я» отвечает обоим: в кабинете по нему
                        // администратор видит, до какого часа открыт доступ.
                        .requestMatchers("/api/admin/me").hasAnyRole(
                                ServiceAdminPrincipal.ROLE, TenantPrincipal.SERVICE_ADMIN_ROLE)
                        // Выйти из кабинета обратно может только тот, кто вошёл
                        // туда из системы администрирования.
                        .requestMatchers("/api/admin/visit/**").hasRole(
                                TenantPrincipal.SERVICE_ADMIN_ROLE)
                        // Всё остальное здесь — список клиентов сервиса и вход
                        // в их кабинеты. Сотруднику разборки, включая владельца,
                        // этого не видно ни запросом, ни экраном: пункт 7
                        // критерия приёмки.
                        .requestMatchers("/api/admin/**").hasRole(ServiceAdminPrincipal.ROLE)
                        // Кто входит в кабинет, решает владелец сам — и
                        // администратор сервиса в его кабинете этого не меняет.
                        // Права у администратора внутри — владельца («делать
                        // абсолютно всё», решение от 8 сентября 2026), но тем же
                        // днём записано и второе: вход только с разрешения
                        // владельца. Заведённый им сотрудник, сменённый им
                        // пароль, включённая им старая учётная запись или
                        // продлённое им самому себе разрешение переживают отзыв:
                        // разрешение закрыто, а вход остался. Разбор PR 393
                        // поймал ровно это — владелец, созданный администратором
                        // (201), и пароль настоящего владельца, сменённый им
                        // (204): после отзыва компания уже не у владельца.
                        //
                        // Правило стоит на семействе путей, а не на методах:
                        // новый способ завести или поменять вход, появившийся
                        // под /api/members или под разрешением администратору,
                        // закрыт от него сразу, а не после того, как кто-то
                        // вспомнит дописать «и не ADMIN» в его @PreAuthorize.
                        // Чтение остаётся: список сотрудников и состояние
                        // разрешения он видит. Остальные роли проходят дальше
                        // так же, как прошли бы правилом ниже.
                        .requestMatchers(HttpMethod.POST, ACCESS_CONTROL).access(CHANGES_ACCESS)
                        .requestMatchers(HttpMethod.PUT, ACCESS_CONTROL).access(CHANGES_ACCESS)
                        .requestMatchers(HttpMethod.PATCH, ACCESS_CONTROL).access(CHANGES_ACCESS)
                        .requestMatchers(HttpMethod.DELETE, ACCESS_CONTROL).access(CHANGES_ACCESS)
                        // «Просмотр» не меняет ничего — это свойство роли,
                        // а не отдельных операций, и потому стоит здесь,
                        // а не на методах, как остальные проверки.
                        //
                        // Расставленная по методам, она обязана быть повторена
                        // в каждом новом эндпоинте, и забыть её можно в любом:
                        // роль, названная «только смотреть», заводила детали
                        // на склад, машины, инвентаризации и удаляла
                        // фотографии — четыре пути, у каждого из которых
                        // просто не было своей проверки. Правило же одно
                        // на все, и здесь оно не забудется.
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole(
                                "OWNER", "MANAGER", "STOREKEEPER", "SELLER")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole(
                                "OWNER", "MANAGER", "STOREKEEPER", "SELLER")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasAnyRole(
                                "OWNER", "MANAGER", "STOREKEEPER", "SELLER")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole(
                                "OWNER", "MANAGER", "STOREKEEPER", "SELLER")
                        // Чтение кабинета — только вошедшему в кабинет. До задачи
                        // 0042 вошедший был один вид, сотрудник разборки, и
                        // «вошёл» означало «есть схема». Администратор в системе
                        // администрирования вошёл, а схемы у него нет: без этого
                        // правила он читал бы пути кабинета, и запрос уходил бы
                        // в public. Изменяющие методы закрыты для него выше —
                        // списком ролей, в котором его роли нет.
                        .requestMatchers("/api/**").access(TENANT_MEMBER)
                        .anyRequest().authenticated())
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        // Отметка о выходе ставится до того, как сессию убьют:
                        // «Вышел в 18:03» — единственный из трёх исходов, про
                        // который известно, что человек ушёл сам, и без этой
                        // строки выход неотличим от истечения. Свои
                        // обработчики Spring зовёт раньше того, который
                        // уничтожает сессию.
                        .addLogoutHandler(logoutRecorder)
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.NO_CONTENT.value())));

        return http.build();
    }

    /**
     * Вошёл ли сотрудник кабинета — то есть есть ли у запроса схема арендатора.
     *
     * <p>Проверка по типу вошедшего, а не по списку ролей: список пришлось бы
     * повторять здесь седьмой копией, и роль, забытая в нём, теряла бы чтение
     * всего кабинета.
     */
    /**
     * Пути, которыми заводят и меняют вход в кабинет: сотрудники (заведение,
     * пароль, включение и выключение) и разрешение администратору сервиса.
     *
     * <p>Сеансы владельца ({@code /api/organization/sessions}) сюда не входят:
     * они только читаются, а завершение чужого сеанса входа не даёт. Перебор
     * от 12 октября 2026: других путей, после которых у кого-то появляется
     * пароль или разрешение, в кабинете нет — приглашений и сброса пароля
     * письмом нет вовсе, а первый владелец заводится провижинингом по секрету.
     */
    static final String[] ACCESS_CONTROL = {
            "/api/members", "/api/members/**", "/api/organization/admin-access/**"};

    /** Роли, которым правило по методам ниже разрешает менять данные. */
    private static final List<String> WRITING_ROLES =
            List.of("OWNER", "MANAGER", "STOREKEEPER", "SELLER");

    /**
     * Менять вход: сотрудник кабинета с пишущей ролью, но не администратор
     * сервиса. Проверка по его собственной роли ({@link TenantPrincipal#isServiceAdmin()}),
     * а не по отсутствию {@code ROLE_OWNER}: полномочия владельца у него есть,
     * и именно они здесь не действуют.
     */
    private static final AuthorizationManager<RequestAuthorizationContext> CHANGES_ACCESS =
            (authentication, context) -> {
                Authentication current = authentication.get();
                return new AuthorizationDecision(current != null && current.isAuthenticated()
                        && current.getPrincipal() instanceof TenantPrincipal member
                        && !member.isServiceAdmin()
                        && WRITING_ROLES.contains(member.role()));
            };

    private static final AuthorizationManager<RequestAuthorizationContext> TENANT_MEMBER =
            (authentication, context) -> {
                Authentication current = authentication.get();
                return new AuthorizationDecision(current != null && current.isAuthenticated()
                        && current.getPrincipal() instanceof TenantPrincipal);
            };

    /**
     * Два входа, два провайдера: кабинет разборки (код компании, логин,
     * пароль) и система администрирования сервиса (логин и пароль из общей
     * схемы ячейки). Различаются они типом токена.
     */
    @Bean
    public AuthenticationManager authenticationManager(MemberAuthenticationProvider provider,
                                                       ServiceAdminAuthenticationProvider admins) {
        return new ProviderManager(provider, admins);
    }

    /**
     * BCrypt: медленный по замыслу, поэтому подбор украденного хеша дорог.
     * Стоимость по умолчанию (10) на 2026 год всё ещё разумна для входа
     * человека — это ~50 мс на проверку.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
