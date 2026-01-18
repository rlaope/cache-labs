package khope.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.config.CacheConfig;
import khope.cache.domain.UserProfile;
import khope.cache.pubsub.CacheMessage;
import khope.cache.pubsub.CacheMessageSubscriber;
import khope.cache.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 유저 캐시 서비스
 * L1(Local) + L2(Redis) 이중 캐시와 Write-Behind 전략 적용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCacheService {

    private final CacheManager localCacheManager;
    private final CacheManager redisCacheManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserProfileRepository userProfileRepository;
    private final CacheMessageSubscriber cacheMessageSubscriber;
    private final ObjectMapper objectMapper;

    @Value("${cache.invalidation-topic:cache-invalidation}")
    private String cacheInvalidationTopic;

    @Value("${cache.write-behind.pending-key:pending-db-updates}")
    private String pendingDbUpdatesKey;

    private static final String USER_CACHE = CacheConfig.USER_CACHE;
    private static final String REDIS_USER_KEY_PREFIX = "user:";

    /**
     * 유저 프로필 조회 (L1 -> L2 -> DB)
     */
    public Optional<UserProfile> getUserProfile(String userId) {
        // 1. L1 (로컬 캐시) 조회
        UserProfile profile = getFromLocalCache(userId);
        if (profile != null) {
            log.debug("L1 캐시 HIT - userId: {}", userId);
            return Optional.of(profile);
        }
        log.debug("L1 캐시 MISS - userId: {}", userId);

        // 2. L2 (Redis) 조회
        profile = getFromRedisCache(userId);
        if (profile != null) {
            log.debug("L2 캐시 HIT - userId: {}", userId);
            putToLocalCache(userId, profile);
            return Optional.of(profile);
        }
        log.debug("L2 캐시 MISS - userId: {}", userId);

        // 3. DB 조회
        Optional<UserProfile> dbProfile = userProfileRepository.findByUserId(userId);
        dbProfile.ifPresent(p -> {
            log.debug("DB 조회 후 캐시 저장 - userId: {}", userId);
            putToLocalCache(userId, p);
            putToRedisCache(userId, p);
        });

        return dbProfile;
    }

    /**
     * 유저 프로필 업데이트 (Write-Behind 전략)
     * 1. Redis(L2) 업데이트
     * 2. Local(L1) 업데이트
     * 3. Pub/Sub으로 타 서버 L1 무효화
     * 4. Write-Behind 큐에 등록 (DB는 배치로 업데이트)
     */
    public void updateUserProfile(String userId, UserProfile profile) {
        log.info("유저 프로필 업데이트 시작 - userId: {}", userId);

        // 1. Redis (L2) 업데이트
        putToRedisCache(userId, profile);

        // 2. Local (L1) 업데이트 (현재 서버)
        putToLocalCache(userId, profile);

        // 3. 타 서버 L1 무효화 메시지 발행
        publishInvalidationMessage(userId);

        // 4. Write-Behind를 위한 변경 로그 기록 (Set으로 중복 제거)
        addToPendingDbUpdates(userId);

        log.info("유저 프로필 업데이트 완료 - userId: {}", userId);
    }

    /**
     * 유저 프로필 즉시 저장 (Write-Through 전략)
     * DB와 캐시에 동시 저장 - 데이터 일관성 보장이 중요한 경우 사용
     */
    @Transactional
    public UserProfile saveUserProfileImmediately(UserProfile profile) {
        log.info("유저 프로필 즉시 저장 - userId: {}", profile.getUserId());

        // 1. DB 저장
        UserProfile saved = userProfileRepository.save(profile);

        // 2. 캐시 업데이트
        putToRedisCache(saved.getUserId(), saved);
        putToLocalCache(saved.getUserId(), saved);

        // 3. 타 서버 L1 무효화
        publishInvalidationMessage(saved.getUserId());

        return saved;
    }

    /**
     * 캐시에서 유저 프로필 삭제
     */
    public void evictUserProfile(String userId) {
        log.info("유저 프로필 캐시 삭제 - userId: {}", userId);

        // L1 삭제
        evictFromLocalCache(userId);

        // L2 삭제
        evictFromRedisCache(userId);

        // 타 서버 L1 무효화
        publishInvalidationMessage(userId);
    }

    // ==================== Private Methods ====================

    private UserProfile getFromLocalCache(String userId) {
        Cache cache = localCacheManager.getCache(USER_CACHE);
        if (cache == null) return null;

        Cache.ValueWrapper wrapper = cache.get(userId);
        if (wrapper == null) return null;

        return (UserProfile) wrapper.get();
    }

    private UserProfile getFromRedisCache(String userId) {
        try {
            String redisKey = REDIS_USER_KEY_PREFIX + userId;
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) return null;

            // ObjectMapper로 변환 (Redis 직렬화 후 역직렬화 시 LinkedHashMap으로 올 수 있음)
            return objectMapper.convertValue(value, UserProfile.class);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 조회 불가: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("L2 캐시 조회 중 오류 발생", e);
            return null;
        }
    }

    private void putToLocalCache(String userId, UserProfile profile) {
        Cache cache = localCacheManager.getCache(USER_CACHE);
        if (cache != null) {
            cache.put(userId, profile);
        }
    }

    private void putToRedisCache(String userId, UserProfile profile) {
        try {
            String redisKey = REDIS_USER_KEY_PREFIX + userId;
            redisTemplate.opsForValue().set(redisKey, profile);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 저장 불가: {}", e.getMessage());
        }
    }

    private void evictFromLocalCache(String userId) {
        Cache cache = localCacheManager.getCache(USER_CACHE);
        if (cache != null) {
            cache.evict(userId);
        }
    }

    private void evictFromRedisCache(String userId) {
        try {
            String redisKey = REDIS_USER_KEY_PREFIX + userId;
            redisTemplate.delete(redisKey);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 삭제 불가: {}", e.getMessage());
        }
    }

    private void publishInvalidationMessage(String userId) {
        try {
            CacheMessage message = CacheMessage.builder()
                    .cacheName(USER_CACHE)
                    .key(userId)
                    .originServerId(cacheMessageSubscriber.getServerId())
                    .evictAll(false)
                    .build();

            String jsonMessage = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(cacheInvalidationTopic, jsonMessage);
            log.debug("캐시 무효화 메시지 발행 - userId: {}", userId);
        } catch (Exception e) {
            log.error("캐시 무효화 메시지 발행 실패", e);
        }
    }

    private void addToPendingDbUpdates(String userId) {
        try {
            // Set을 사용하여 중복 제거 (동일 userId 여러 번 업데이트해도 한 번만 DB 저장)
            redisTemplate.opsForSet().add(pendingDbUpdatesKey, userId);
            log.debug("Write-Behind 큐에 등록 - userId: {}", userId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 Write-Behind 큐 등록 불가: {}", e.getMessage());
        }
    }
}
