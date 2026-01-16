package khope.cache.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 테스트용 Embedded Redis 설정
 *
 * 참고: macOS ARM (M1/M2)에서는 embedded-redis가 동작하지 않을 수 있음
 * 이 경우 로컬 Redis를 사용하거나 Redis 없이 테스트 진행
 */
@Configuration
@Profile("test")
public class EmbeddedRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedRedisConfig.class);

    @Value("${spring.data.redis.port:6370}")
    private int redisPort;

    private Object redisServer; // Redis 서버 (동적 로딩)

    @PostConstruct
    public void startRedis() {
        try {
            // macOS ARM 체크
            String osArch = System.getProperty("os.arch");
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("mac") && osArch.equals("aarch64")) {
                log.warn("macOS ARM(M1/M2) 환경입니다. Embedded Redis를 건너뜁니다.");
                log.warn("Redis 관련 테스트는 로컬 Redis(localhost:6379)가 필요합니다.");
                return;
            }

            // Embedded Redis 시작 시도
            Class<?> redisServerClass = Class.forName("redis.embedded.RedisServer");
            Object builder = redisServerClass.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("port", int.class).invoke(builder, redisPort);
            builder = builder.getClass().getMethod("setting", String.class).invoke(builder, "maxmemory 128M");
            redisServer = builder.getClass().getMethod("build").invoke(builder);
            redisServer.getClass().getMethod("start").invoke(redisServer);

            log.info("Embedded Redis 시작됨 (포트: {})", redisPort);
        } catch (Exception e) {
            log.warn("Embedded Redis 시작 실패: {}. Redis 없이 테스트를 진행합니다.", e.getMessage());
        }
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null) {
            try {
                Boolean isActive = (Boolean) redisServer.getClass().getMethod("isActive").invoke(redisServer);
                if (isActive) {
                    redisServer.getClass().getMethod("stop").invoke(redisServer);
                    log.info("Embedded Redis 중지됨");
                }
            } catch (Exception e) {
                log.warn("Embedded Redis 중지 실패: {}", e.getMessage());
            }
        }
    }
}