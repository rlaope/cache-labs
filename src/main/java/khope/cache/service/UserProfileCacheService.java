package khope.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.migration.dto.UserProfileV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 유저 프로필 캐시 서비스 (클라이언트용)
 *
 * @JsonAlias를 활용한 V1/V2 호환 처리:
 * - V1 JSON {"name": "khope"} → UserProfileV2.username = "khope"
 * - V2 JSON {"username": "khope"} → UserProfileV2.username = "khope"
 *
 * 별도 마이그레이션 로직 없이 DTO의 @JsonAlias로 유연하게 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "user:profile:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    /**
     * 유저 프로필 조회
     * V1, V2 JSON 모두 UserProfileV2로 역직렬화 (@JsonAlias 활용)
     */
    public Optional<UserProfileV2> get(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            String json = stringRedisTemplate.opsForValue().get(key);

            if (json == null) {
                log.debug("캐시 MISS - userId: {}", userId);
                return Optional.empty();
            }

            // @JsonAlias 덕분에 V1(name), V2(username) 모두 처리 가능
            UserProfileV2 profile = objectMapper.readValue(json, UserProfileV2.class);
            log.debug("캐시 HIT - userId: {}", userId);

            return Optional.of(profile);

        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패 - userId: {}", userId, e);
            return Optional.empty();
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패 - userId: {}", userId);
            return Optional.empty();
        }
    }

    /**
     * 유저 프로필 저장
     * 항상 V2 형식(username)으로 저장 → 점진적 마이그레이션
     */
    public void set(String userId, UserProfileV2 profile) {
        set(userId, profile, DEFAULT_TTL);
    }

    public void set(String userId, UserProfileV2 profile, Duration ttl) {
        try {
            String key = KEY_PREFIX + userId;
            // 저장할 때는 항상 V2 형식 (username 필드)
            String json = objectMapper.writeValueAsString(profile);

            stringRedisTemplate.opsForValue().set(key, json, ttl);
            log.debug("캐시 저장 - userId: {}", userId);

        } catch (JsonProcessingException e) {
            log.error("JSON 직렬화 실패 - userId: {}", userId, e);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패 - userId: {}", userId);
        }
    }

    /**
     * 조회 후 저장 (Read-Through + Lazy Migration)
     * V1 데이터 읽으면 자동으로 V2로 재저장
     */
    public Optional<UserProfileV2> getAndMigrateIfNeeded(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            String json = stringRedisTemplate.opsForValue().get(key);

            if (json == null) {
                return Optional.empty();
            }

            UserProfileV2 profile = objectMapper.readValue(json, UserProfileV2.class);

            // V1 데이터였으면 V2 형식으로 재저장 (Lazy Migration)
            if (json.contains("\"name\"") && !json.contains("\"username\"")) {
                log.info("V1 → V2 Lazy Migration - userId: {}", userId);
                Long ttl = stringRedisTemplate.getExpire(key);
                Duration duration = (ttl != null && ttl > 0) ? Duration.ofSeconds(ttl) : DEFAULT_TTL;
                set(userId, profile, duration);
            }

            return Optional.of(profile);

        } catch (JsonProcessingException e) {
            log.error("JSON 파싱 실패 - userId: {}", userId, e);
            return Optional.empty();
        }
    }

    public void delete(String userId) {
        String key = KEY_PREFIX + userId;
        stringRedisTemplate.delete(key);
        log.debug("캐시 삭제 - userId: {}", userId);
    }
}
