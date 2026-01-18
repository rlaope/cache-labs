package khope.cache.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 캐시 스키마 마이그레이션 서비스
 *
 * Lazy Migration: 읽기 시점에 구버전 감지 → 신버전 변환 → 캐시 갱신
 *
 * 스키마 변경 내역:
 * - v1 → v2: name → username 필드명 변경
 */
@Slf4j
@Service
public class SchemaMigrationService {

    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final String VERSION_FIELD = "_schemaVersion";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Lua 스크립트 (서버 사이드 마이그레이션용)
    private RedisScript<Long> migrateUserScript;

    // 마이그레이션 통계
    private final AtomicLong lazyMigrationCount = new AtomicLong(0);
    private final AtomicLong alreadyMigratedCount = new AtomicLong(0);
    private final AtomicLong migrationErrorCount = new AtomicLong(0);

    @Autowired
    public SchemaMigrationService(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Lua 스크립트 로드
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("scripts/migrate_user.lua")));
        script.setResultType(Long.class);
        this.migrateUserScript = script;
        log.info("마이그레이션 Lua 스크립트 로드 완료");
    }

    /**
     * 캐시에서 데이터를 읽고 Lazy Migration 적용
     *
     * @param key Redis 키
     * @param type 변환할 타입
     * @return 마이그레이션된 데이터 (없으면 null)
     */
    public <T> T getWithMigration(String key, Class<T> type) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }

            // JSON 파싱
            JsonNode node = objectMapper.readTree(json);

            // 버전 체크 및 마이그레이션
            if (needsMigration(node)) {
                log.debug("구버전 감지, Lazy Migration 시작 - key: {}", key);

                JsonNode migrated = migrateToCurrentVersion(node);
                String migratedJson = objectMapper.writeValueAsString(migrated);

                // TTL 유지하면서 저장
                Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
                if (ttl != null && ttl > 0) {
                    stringRedisTemplate.opsForValue().set(key, migratedJson, Duration.ofSeconds(ttl));
                } else {
                    stringRedisTemplate.opsForValue().set(key, migratedJson);
                }

                lazyMigrationCount.incrementAndGet();
                log.info("Lazy Migration 완료 - key: {}, 총 마이그레이션: {}건",
                        key, lazyMigrationCount.get());

                return objectMapper.treeToValue(migrated, type);
            }

            alreadyMigratedCount.incrementAndGet();
            return objectMapper.treeToValue(node, type);

        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패 - key: {}", key, e);
            migrationErrorCount.incrementAndGet();
            return null;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패 - key: {}", key);
            return null;
        }
    }

    /**
     * 데이터 저장 (항상 최신 버전으로)
     */
    public void saveWithVersion(String key, Object data, Duration ttl) {
        try {
            JsonNode node = objectMapper.valueToTree(data);
            ObjectNode objectNode = (ObjectNode) node;

            // 스키마 버전 추가
            objectNode.put(VERSION_FIELD, CURRENT_SCHEMA_VERSION);

            String json = objectMapper.writeValueAsString(objectNode);

            if (ttl != null) {
                stringRedisTemplate.opsForValue().set(key, json, ttl);
            } else {
                stringRedisTemplate.opsForValue().set(key, json);
            }

        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 실패", e);
        }
    }

    /**
     * Lua 스크립트를 사용한 서버 사이드 마이그레이션
     * 백그라운드 워커에서 사용
     *
     * @return 1: 변환됨, 0: 이미 최신, -1: 키 없음, -2: 파싱 실패
     */
    public Long migrateWithLuaScript(String key) {
        try {
            return stringRedisTemplate.execute(
                    migrateUserScript,
                    Collections.singletonList(key)
            );
        } catch (Exception e) {
            log.error("Lua 스크립트 실행 실패 - key: {}", key, e);
            return -2L;
        }
    }

    /**
     * 마이그레이션 필요 여부 확인
     */
    public boolean needsMigration(JsonNode node) {
        // 버전 필드가 있으면 버전 체크
        if (node.has(VERSION_FIELD)) {
            int version = node.get(VERSION_FIELD).asInt();
            return version < CURRENT_SCHEMA_VERSION;
        }

        // 버전 필드가 없으면 구버전 구조로 판단
        // v1: name 필드 있고 username 필드 없음
        return node.has("name") && !node.has("username");
    }

    /**
     * 구버전 → 현재 버전으로 마이그레이션
     */
    private JsonNode migrateToCurrentVersion(JsonNode node) {
        ObjectNode result = node.deepCopy();

        // v1 → v2: name → username
        if (result.has("name") && !result.has("username")) {
            result.put("username", result.get("name").asText());
            result.remove("name");
            log.debug("v1→v2 마이그레이션: name → username");
        }

        // 버전 정보 추가
        result.put(VERSION_FIELD, CURRENT_SCHEMA_VERSION);

        return result;
    }

    /**
     * 마이그레이션 통계 조회
     */
    public MigrationStats getStats() {
        return new MigrationStats(
                lazyMigrationCount.get(),
                alreadyMigratedCount.get(),
                migrationErrorCount.get()
        );
    }

    /**
     * 통계 초기화
     */
    public void resetStats() {
        lazyMigrationCount.set(0);
        alreadyMigratedCount.set(0);
        migrationErrorCount.set(0);
    }

    public record MigrationStats(
            long lazyMigrationCount,
            long alreadyMigratedCount,
            long errorCount
    ) {
        public long totalProcessed() {
            return lazyMigrationCount + alreadyMigratedCount;
        }

        public double migrationRate() {
            long total = totalProcessed();
            return total > 0 ? (double) lazyMigrationCount / total * 100 : 0;
        }
    }
}
