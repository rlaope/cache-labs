package khope.cache.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.migration.dto.UserProfileV1;
import khope.cache.migration.dto.UserProfileV2;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 스키마 마이그레이션 통합 테스트
 *
 * 시나리오: name → username 필드명 변경
 *
 * 테스트 내용:
 * 1. V1(구버전) JSON 데이터 → V2(신버전)으로 Lazy Migration
 * 2. Lua 스크립트를 사용한 서버 사이드 마이그레이션
 * 3. 대량 데이터 마이그레이션 성능
 * 4. 동시 접근 시 데이터 정합성
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("스키마 마이그레이션 테스트")
class SchemaMigrationTest {

    @Autowired(required = false)
    private SchemaMigrationService migrationService;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "user:test:";
    private boolean redisAvailable;

    @BeforeEach
    void setUp() {
        // Redis 연결 확인
        try {
            if (stringRedisTemplate != null) {
                stringRedisTemplate.opsForValue().set("ping", "pong", Duration.ofSeconds(1));
                redisAvailable = true;
            }
        } catch (Exception e) {
            redisAvailable = false;
        }
    }

    @AfterEach
    void tearDown() {
        if (redisAvailable && stringRedisTemplate != null) {
            // 테스트 키 정리
            try {
                stringRedisTemplate.delete(stringRedisTemplate.keys(KEY_PREFIX + "*"));
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(1)
    @DisplayName("V1 JSON 데이터가 V2로 Lazy Migration 되는지 검증")
    void testLazyMigrationFromV1ToV2() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V1 형식의 JSON 데이터 저장
        String key = KEY_PREFIX + "lazy1";
        UserProfileV1 v1Data = UserProfileV1.sample("khope");
        String v1Json = objectMapper.writeValueAsString(v1Data);

        System.out.println("=== V1 원본 데이터 ===");
        System.out.println(v1Json);

        stringRedisTemplate.opsForValue().set(key, v1Json, Duration.ofMinutes(5));

        // When: 마이그레이션 서비스로 조회
        UserProfileV2 result = migrationService.getWithMigration(key, UserProfileV2.class);

        // Then: V2 형식으로 변환되었는지 확인
        System.out.println("\n=== 마이그레이션 후 결과 ===");
        System.out.println("username: " + result.getUsername());
        System.out.println("email: " + result.getEmail());

        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo("khope");  // name → username
        assertThat(result.getEmail()).isEqualTo("khope@example.com");
        assertThat(result.getPoints()).isEqualTo(1000L);

        // Redis에 저장된 데이터도 V2 형식인지 확인
        String storedJson = stringRedisTemplate.opsForValue().get(key);
        System.out.println("\n=== Redis에 저장된 마이그레이션된 데이터 ===");
        System.out.println(storedJson);

        JsonNode storedNode = objectMapper.readTree(storedJson);
        assertThat(storedNode.has("username")).isTrue();
        assertThat(storedNode.has("name")).isFalse();  // name 필드 제거됨
        assertThat(storedNode.get("username").asText()).isEqualTo("khope");

        // 통계 확인
        SchemaMigrationService.MigrationStats stats = migrationService.getStats();
        System.out.println("\n=== 마이그레이션 통계 ===");
        System.out.println("Lazy 마이그레이션: " + stats.lazyMigrationCount() + "건");
        assertThat(stats.lazyMigrationCount()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    @DisplayName("이미 V2인 데이터는 변환하지 않음")
    void testAlreadyV2DataSkipped() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V2 형식의 JSON 데이터 저장
        String key = KEY_PREFIX + "already-v2";
        UserProfileV2 v2Data = UserProfileV2.sample("already-migrated");
        String v2Json = objectMapper.writeValueAsString(v2Data);

        System.out.println("=== V2 원본 데이터 ===");
        System.out.println(v2Json);

        stringRedisTemplate.opsForValue().set(key, v2Json, Duration.ofMinutes(5));
        migrationService.resetStats();

        // When: 마이그레이션 서비스로 조회
        UserProfileV2 result = migrationService.getWithMigration(key, UserProfileV2.class);

        // Then: 변환 없이 그대로 반환
        assertThat(result.getUsername()).isEqualTo("already-migrated");

        // 마이그레이션 카운트는 0이어야 함
        SchemaMigrationService.MigrationStats stats = migrationService.getStats();
        System.out.println("\n=== 통계 (변환 없음) ===");
        System.out.println("Lazy 마이그레이션: " + stats.lazyMigrationCount() + "건");
        System.out.println("이미 최신: " + stats.alreadyMigratedCount() + "건");

        assertThat(stats.lazyMigrationCount()).isZero();
        assertThat(stats.alreadyMigratedCount()).isGreaterThan(0);
    }

    @Test
    @Order(3)
    @DisplayName("Lua 스크립트로 서버 사이드 마이그레이션")
    void testLuaScriptMigration() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V1 형식의 JSON 데이터 저장
        String key = KEY_PREFIX + "lua-test";
        UserProfileV1 v1Data = UserProfileV1.builder()
                .name("lua-user")
                .email("lua@example.com")
                .points(500L)
                .bio("Lua script test")
                .build();
        String v1Json = objectMapper.writeValueAsString(v1Data);

        System.out.println("=== Lua 마이그레이션 전 ===");
        System.out.println(v1Json);

        stringRedisTemplate.opsForValue().set(key, v1Json, Duration.ofMinutes(5));

        // When: Lua 스크립트로 마이그레이션
        Long result = migrationService.migrateWithLuaScript(key);

        // Then: 마이그레이션 성공 (return 1)
        System.out.println("\n=== Lua 스크립트 결과 ===");
        System.out.println("반환값: " + result + " (1=변환됨, 0=이미 최신, -1=키 없음)");

        assertThat(result).isEqualTo(1L);

        // Redis 데이터 확인
        String migratedJson = stringRedisTemplate.opsForValue().get(key);
        System.out.println("\n=== Lua 마이그레이션 후 ===");
        System.out.println(migratedJson);

        JsonNode node = objectMapper.readTree(migratedJson);
        assertThat(node.has("username")).isTrue();
        assertThat(node.has("name")).isFalse();
        assertThat(node.get("username").asText()).isEqualTo("lua-user");

        // 다시 실행하면 0 반환 (이미 마이그레이션됨)
        Long secondResult = migrationService.migrateWithLuaScript(key);
        assertThat(secondResult).isEqualTo(0L);
    }

    @Test
    @Order(4)
    @DisplayName("대량 데이터 마이그레이션 성능 테스트")
    void testBulkMigrationPerformance() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        int dataCount = 100;

        // Given: 대량의 V1 데이터 생성
        System.out.println("=== 대량 데이터 생성 ===");
        System.out.println("데이터 수: " + dataCount);

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < dataCount; i++) {
            String key = KEY_PREFIX + "bulk:" + i;
            UserProfileV1 v1Data = UserProfileV1.sample("user" + i);
            String json = objectMapper.writeValueAsString(v1Data);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofMinutes(5));
            keys.add(key);
        }

        migrationService.resetStats();

        // When: Lazy Migration으로 모든 데이터 조회
        long startTime = System.currentTimeMillis();

        for (String key : keys) {
            migrationService.getWithMigration(key, UserProfileV2.class);
        }

        long duration = System.currentTimeMillis() - startTime;

        // Then: 성능 및 정확성 확인
        SchemaMigrationService.MigrationStats stats = migrationService.getStats();

        System.out.println("\n=== 대량 마이그레이션 결과 ===");
        System.out.println("총 처리: " + stats.totalProcessed() + "건");
        System.out.println("마이그레이션: " + stats.lazyMigrationCount() + "건");
        System.out.println("소요 시간: " + duration + "ms");
        System.out.println("처리량: " + (dataCount * 1000 / Math.max(duration, 1)) + " 건/초");

        assertThat(stats.lazyMigrationCount()).isEqualTo(dataCount);
        assertThat(stats.errorCount()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("Lua 스크립트 대량 마이그레이션 성능 비교")
    void testLuaScriptBulkPerformance() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        int dataCount = 100;

        // Given: 대량의 V1 데이터 생성
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < dataCount; i++) {
            String key = KEY_PREFIX + "lua-bulk:" + i;
            UserProfileV1 v1Data = UserProfileV1.sample("luauser" + i);
            String json = objectMapper.writeValueAsString(v1Data);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofMinutes(5));
            keys.add(key);
        }

        // When: Lua 스크립트로 마이그레이션
        long startTime = System.currentTimeMillis();
        int migrated = 0;

        for (String key : keys) {
            Long result = migrationService.migrateWithLuaScript(key);
            if (result != null && result == 1) {
                migrated++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Then
        System.out.println("=== Lua 스크립트 대량 마이그레이션 ===");
        System.out.println("마이그레이션: " + migrated + "건");
        System.out.println("소요 시간: " + duration + "ms");
        System.out.println("처리량: " + (dataCount * 1000 / Math.max(duration, 1)) + " 건/초");

        assertThat(migrated).isEqualTo(dataCount);
    }

    @Test
    @Order(6)
    @DisplayName("동시 접근 시 데이터 정합성 테스트")
    void testConcurrentMigration() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: 하나의 V1 데이터
        String key = KEY_PREFIX + "concurrent";
        UserProfileV1 v1Data = UserProfileV1.sample("concurrent-user");
        String v1Json = objectMapper.writeValueAsString(v1Data);
        stringRedisTemplate.opsForValue().set(key, v1Json, Duration.ofMinutes(5));

        // When: 여러 스레드에서 동시에 마이그레이션 시도
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<UserProfileV2>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await(); // 모든 스레드가 동시에 시작
                    return migrationService.getWithMigration(key, UserProfileV2.class);
                } catch (Exception e) {
                    return null;
                }
            }));
        }

        // Then: 모든 결과가 동일해야 함
        List<UserProfileV2> results = new ArrayList<>();
        for (Future<UserProfileV2> future : futures) {
            UserProfileV2 result = future.get(5, TimeUnit.SECONDS);
            if (result != null) {
                results.add(result);
            }
        }

        executor.shutdown();

        System.out.println("=== 동시성 테스트 결과 ===");
        System.out.println("성공한 요청: " + results.size() + "/" + threadCount);

        assertThat(results).isNotEmpty();

        // 모든 결과의 username이 동일해야 함
        String expectedUsername = "concurrent-user";
        for (UserProfileV2 result : results) {
            assertThat(result.getUsername()).isEqualTo(expectedUsername);
        }

        // 최종 Redis 데이터 확인
        String finalJson = stringRedisTemplate.opsForValue().get(key);
        JsonNode finalNode = objectMapper.readTree(finalJson);
        assertThat(finalNode.get("username").asText()).isEqualTo(expectedUsername);
        assertThat(finalNode.has("name")).isFalse();

        System.out.println("최종 데이터: " + finalJson);
    }

    @Test
    @Order(7)
    @DisplayName("TTL이 유지되는지 검증")
    void testTtlPreservation() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: TTL 설정된 V1 데이터
        String key = KEY_PREFIX + "ttl-test";
        UserProfileV1 v1Data = UserProfileV1.sample("ttl-user");
        String v1Json = objectMapper.writeValueAsString(v1Data);

        long originalTtlSeconds = 300; // 5분
        stringRedisTemplate.opsForValue().set(key, v1Json, Duration.ofSeconds(originalTtlSeconds));

        // 저장 직후 TTL 확인
        Long ttlBefore = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        System.out.println("=== TTL 테스트 ===");
        System.out.println("마이그레이션 전 TTL: " + ttlBefore + "초");

        // When: 마이그레이션 실행
        migrationService.getWithMigration(key, UserProfileV2.class);

        // Then: TTL이 유지되어야 함
        Long ttlAfter = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        System.out.println("마이그레이션 후 TTL: " + ttlAfter + "초");

        assertThat(ttlAfter).isNotNull();
        assertThat(ttlAfter).isGreaterThan(0);
        // TTL은 마이그레이션 시점에 살짝 줄어들 수 있으므로 여유 있게 확인
        assertThat(ttlAfter).isGreaterThan(originalTtlSeconds - 10);
    }

    @Test
    @Order(8)
    @DisplayName("다양한 JSON 구조 마이그레이션")
    void testVariousJsonStructures() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Case 1: 최소 데이터 (name만 있음)
        String key1 = KEY_PREFIX + "minimal";
        stringRedisTemplate.opsForValue().set(key1,
                "{\"name\":\"minimal-user\"}",
                Duration.ofMinutes(5));

        UserProfileV2 result1 = migrationService.getWithMigration(key1, UserProfileV2.class);
        assertThat(result1.getUsername()).isEqualTo("minimal-user");
        System.out.println("Case 1 (최소 데이터): " + result1.getUsername());

        // Case 2: 추가 필드가 있는 경우 (하위호환)
        String key2 = KEY_PREFIX + "extra-fields";
        stringRedisTemplate.opsForValue().set(key2,
                "{\"name\":\"extra-user\",\"email\":\"extra@test.com\",\"unknownField\":\"ignored\"}",
                Duration.ofMinutes(5));

        UserProfileV2 result2 = migrationService.getWithMigration(key2, UserProfileV2.class);
        assertThat(result2.getUsername()).isEqualTo("extra-user");
        assertThat(result2.getEmail()).isEqualTo("extra@test.com");
        System.out.println("Case 2 (추가 필드): " + result2.getUsername());

        // Case 3: 숫자 필드
        String key3 = KEY_PREFIX + "with-numbers";
        stringRedisTemplate.opsForValue().set(key3,
                "{\"name\":\"number-user\",\"points\":9999,\"bio\":\"test\"}",
                Duration.ofMinutes(5));

        UserProfileV2 result3 = migrationService.getWithMigration(key3, UserProfileV2.class);
        assertThat(result3.getUsername()).isEqualTo("number-user");
        assertThat(result3.getPoints()).isEqualTo(9999L);
        System.out.println("Case 3 (숫자 필드): points=" + result3.getPoints());

        System.out.println("\n모든 케이스 성공!");
    }
}
