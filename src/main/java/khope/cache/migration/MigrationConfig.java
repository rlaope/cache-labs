package khope.cache.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 마이그레이션 관련 설정
 */
@Configuration
public class MigrationConfig {

    // StringRedisTemplate은 Spring Boot에서 자동 생성됨 (RedisAutoConfiguration)
    // 별도 정의 불필요

    /**
     * ObjectMapper 커스터마이징
     * Spring Boot 자동 설정된 ObjectMapper를 사용하되 추가 설정
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer objectMapperCustomizer() {
        return builder -> {
            // unknown 필드 무시 (하위호환성)
            builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            // 날짜 처리
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.modules(new JavaTimeModule());
        };
    }
}
