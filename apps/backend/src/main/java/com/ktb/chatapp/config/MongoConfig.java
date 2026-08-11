package com.ktb.chatapp.config;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer() {
        // BE 10대 x maxSize -> 프라이머리로 몰리는 총 커넥션 수. 100이면 최대 1000까지
        // 열려서 mongod 컨텍스트스위칭 낭비 발생. 25로 낮춰 10대 합계 250으로 제한.
        return builder -> builder.applyToConnectionPoolSettings(pool -> pool
                .maxSize(25)
                .minSize(5)
                .maxConnectionIdleTime(60, TimeUnit.SECONDS)
                .maxWaitTime(5, TimeUnit.SECONDS));
    }
}
