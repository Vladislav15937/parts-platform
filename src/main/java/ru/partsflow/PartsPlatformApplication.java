package ru.partsflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.partsflow.platform.tenant.SchemaSyncCommand;

@SpringBootApplication
@EnableScheduling
public class PartsPlatformApplication {

    /**
     * Тот же артефакт умеет два запуска: обслуживать людей и привести схемы
     * к своей версии.
     *
     * <p>Второй нужен ровно потому, что changelog лежит <b>внутри</b> jar:
     * накатить новые changeset'ы может только новый артефакт, а выложить
     * новый код на старые схемы нельзя. Разорвать это можно единственным
     * способом — запустить новый образ одноразовым контейнером, который
     * ничего не обслуживает: см. {@link SchemaSyncCommand}.
     *
     * <p>Развилка стоит здесь, а не профилем Spring: контексты у двух
     * режимов разные, и выбирать между ними надо до того, как поднялся
     * хоть один.
     */
    public static void main(String[] args) {
        if (SchemaSyncCommand.requested(args)) {
            System.exit(SchemaSyncCommand.run(args));
        }
        SpringApplication.run(PartsPlatformApplication.class, args);
    }
}
