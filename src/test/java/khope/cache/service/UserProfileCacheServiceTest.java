package khope.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.migration.dto.UserProfileV2;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 클라이언트 서비스에서 @JsonAlias 활용한 V1/V2 호환 테스트
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("UserProfileCacheService - V1/V2 호환 테스트")
class UserProfileCacheServiceTest {

    @Autowired(required = false)
    private UserProfileCacheService cacheService;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private boolean redisAvailable;
    private static final String TEST_USER_ID = "test-user-123";

    @BeforeEach
    void setUp() {
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
        if (redisAvailable && cacheService != null) {
            cacheService.delete(TEST_USER_ID);
            cacheService.delete("v1-user");
            cacheService.delete("v2-user");
        }
    }

    @Test
    @Order(1)
    @DisplayName("V1 JSON을 V2 클래스로 읽기 (@JsonAlias)")
    void testReadV1JsonAsV2() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V1 형식 JSON 직접 저장 (name 필드)
        String v1Json = """
            {
                "name": "khope",
                "email": "khope@example.com",
                "points": 1000,
                "bio": "Hello"
            }
            """;
        stringRedisTemplate.opsForValue().set("user:profile:v1-user", v1Json, Duration.ofMinutes(5));

        System.out.println("=== V1 JSON 저장 ===");
        System.out.println(v1Json);

        // When: V2 클래스로 조회
        Optional<UserProfileV2> result = cacheService.get("v1-user");

        // Then: @JsonAlias로 name → username 매핑
        System.out.println("\n=== V2 클래스로 역직렬화 ===");
        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("khope");  // name이 username으로!
        assertThat(result.get().getEmail()).isEqualTo("khope@example.com");

        System.out.println("username: " + result.get().getUsername());
        System.out.println("email: " + result.get().getEmail());
    }

    @Test
    @Order(2)
    @DisplayName("V2 JSON을 V2 클래스로 읽기")
    void testReadV2JsonAsV2() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V2 형식 JSON 직접 저장 (username 필드)
        String v2Json = """
            {
                "username": "khope-v2",
                "email": "khope@example.com",
                "points": 2000,
                "bio": "V2 user"
            }
            """;
        stringRedisTemplate.opsForValue().set("user:profile:v2-user", v2Json, Duration.ofMinutes(5));

        System.out.println("=== V2 JSON 저장 ===");
        System.out.println(v2Json);

        // When: V2 클래스로 조회
        Optional<UserProfileV2> result = cacheService.get("v2-user");

        // Then: 정상 매핑
        System.out.println("\n=== V2 클래스로 역직렬화 ===");
        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("khope-v2");

        System.out.println("username: " + result.get().getUsername());
    }

    @Test
    @Order(3)
    @DisplayName("저장할 때는 항상 V2 형식으로")
    void testAlwaysSaveAsV2() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given
        UserProfileV2 profile = UserProfileV2.builder()
                .username("new-user")
                .email("new@example.com")
                .points(500L)
                .build();

        // When: 저장
        cacheService.set(TEST_USER_ID, profile);

        // Then: Redis에 V2 형식(username)으로 저장됨
        String storedJson = stringRedisTemplate.opsForValue().get("user:profile:" + TEST_USER_ID);

        System.out.println("=== 저장된 JSON ===");
        System.out.println(storedJson);

        assertThat(storedJson).contains("\"username\"");
        assertThat(storedJson).doesNotContain("\"name\"");  // name 필드 없음
    }

    @Test
    @Order(4)
    @DisplayName("getAndMigrateIfNeeded - V1 읽으면 V2로 재저장")
    void testLazyMigrationOnRead() throws Exception {
        assumeTrue(redisAvailable, "Redis 연결 필요");

        // Given: V1 형식 JSON 저장
        String v1Json = "{\"name\":\"migrate-me\",\"email\":\"m@test.com\",\"points\":100}";
        stringRedisTemplate.opsForValue().set("user:profile:" + TEST_USER_ID, v1Json, Duration.ofMinutes(5));

        System.out.println("=== Before Migration ===");
        System.out.println(stringRedisTemplate.opsForValue().get("user:profile:" + TEST_USER_ID));

        // When: getAndMigrateIfNeeded 호출
        Optional<UserProfileV2> result = cacheService.getAndMigrateIfNeeded(TEST_USER_ID);

        // Then: 결과는 정상
        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("migrate-me");

        // And: Redis에 V2 형식으로 재저장됨
        String afterJson = stringRedisTemplate.opsForValue().get("user:profile:" + TEST_USER_ID);

        System.out.println("\n=== After Migration ===");
        System.out.println(afterJson);

        assertThat(afterJson).contains("\"username\"");
        assertThat(afterJson).doesNotContain("\"name\"");
    }
}
