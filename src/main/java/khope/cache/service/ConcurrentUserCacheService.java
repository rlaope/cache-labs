package khope.cache.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.config.CacheConfig;
import khope.cache.domain.UserProfile;
import khope.cache.pubsub.CacheMessage;
import khope.cache.pubsub.CacheMessageSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 동시성을 고려한 유저 캐시 서비스
 * 분산락 + 멱등성 처리로 따닥(중복 요청) 방지
 */
@Slf4j
@Service
public class ConcurrentUserCacheService {

    private final RedissonClient redissonClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheManager localCacheManager;
    private final CacheMessageSubscriber cacheMessageSubscriber;
    private final ObjectMapper objectMapper;

    public ConcurrentUserCacheService(
            @Autowired(required = false) RedissonClient redissonClient,
            RedisTemplate<String, Object> redisTemplate,
            CacheManager localCacheManager,
            CacheMessageSubscriber cacheMessageSubscriber,
            ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.localCacheManager = localCacheManager;
        this.cacheMessageSubscriber = cacheMessageSubscriber;
        this.objectMapper = objectMapper;

        if (redissonClient == null) {
            log.warn("RedissonClient가 없습니다. 분산락 기능이 비활성화됩니다.");
        }
    }

    @Value("${cache.invalidation-topic:cache-invalidation}")
    private String cacheInvalidationTopic;

    @Value("${cache.write-behind.pending-key:pending-db-updates}")
    private String pendingDbUpdatesKey;

    private static final String USER_CACHE = CacheConfig.USER_CACHE;
    private static final String REDIS_USER_KEY_PREFIX = "user:";
    private static final String LOCK_KEY_PREFIX = "lock:user:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "req:";

    // 락 설정
    private static final long LOCK_WAIT_TIME_SECONDS = 5;
    private static final long LOCK_LEASE_TIME_SECONDS = 10;

    // 멱등성 키 TTL
    private static final long IDEMPOTENCY_TTL_MINUTES = 5;

    /**
     * 분산락 + 멱등성을 적용한 유저 프로필 업데이트
     *
     * @param requestId 요청 ID (클라이언트에서 전달, 멱등성 보장용)
     * @param userId    유저 ID
     * @param profile   업데이트할 프로필
     * @return 업데이트 성공 여부
     */
    public boolean updateUserProfileWithLock(String requestId, String userId, UserProfile profile) {
        // RedissonClient가 없으면 락 없이 업데이트 (테스트 환경 등)
        if (redissonClient == null) {
            log.warn("분산락 사용 불가, 락 없이 업데이트 진행 - userId: {}", userId);
            if (!checkAndSetIdempotencyKey(requestId)) {
                log.info("중복 요청 감지 - requestId: {}, userId: {}", requestId, userId);
                return false;
            }
            updateCacheAndPublish(userId, profile);
            return true;
        }

        String lockKey = LOCK_KEY_PREFIX + userId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 1. 락 획득 시도 (최대 5초 대기, 10초간 점유)
            if (lock.tryLock(LOCK_WAIT_TIME_SECONDS, LOCK_LEASE_TIME_SECONDS, TimeUnit.SECONDS)) {
                try {
                    // 2. 멱등성 체크 (중복 요청 방지)
                    if (!checkAndSetIdempotencyKey(requestId)) {
                        log.info("중복 요청 감지 - requestId: {}, userId: {}", requestId, userId);
                        return false; // 이미 처리된 요청
                    }

                    // 3. 비즈니스 로직 및 캐시 업데이트 (원자적 수행)
                    updateCacheAndPublish(userId, profile);

                    log.info("유저 프로필 업데이트 성공 - requestId: {}, userId: {}", requestId, userId);
                    return true;

                } finally {
                    // 4. 락 해제
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                log.warn("락 획득 실패 - userId: {} (다른 요청 처리 중)", userId);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("락 획득 중 인터럽트 발생", e);
            return false;
        } catch (Exception e) {
            log.error("유저 프로필 업데이트 중 오류 발생", e);
            return false;
        }
    }

    /**
     * 멱등성 키 체크 및 설정
     * 첫 번째 요청이면 true, 중복 요청이면 false 반환
     */
    private boolean checkAndSetIdempotencyKey(String requestId) {
        try {
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + requestId;
            Boolean isFirstRequest = redisTemplate.opsForValue()
                    .setIfAbsent(idempotencyKey, "processing", Duration.ofMinutes(IDEMPOTENCY_TTL_MINUTES));

            return Boolean.TRUE.equals(isFirstRequest);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 멱등성 체크 불가, 요청 처리 진행: {}", e.getMessage());
            return true; // Redis 장애 시 요청 허용 (정합성보다 가용성 우선)
        }
    }

    /**
     * Redis 트랜잭션을 사용한 원자적 캐시 업데이트
     * 1. Redis(L2)에 데이터 저장
     * 2. Write-Behind 큐에 등록 (Set 사용으로 중복 제거)
     * 3. Pub/Sub으로 무효화 메시지 발행
     */
    private void updateCacheAndPublish(String userId, UserProfile profile) {
        String redisKey = REDIS_USER_KEY_PREFIX + userId;

        try {
            // Redis 트랜잭션으로 원자적 수행
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) throws DataAccessException {
                    operations.multi(); // 트랜잭션 시작

                    // 1. Redis(L2)에 데이터 저장
                    operations.opsForValue().set(redisKey, profile);

                    // 2. Write-Behind 큐에 등록 (Set으로 중복 제거)
                    operations.opsForSet().add(pendingDbUpdatesKey, userId);

                    return operations.exec(); // 트랜잭션 커밋
                }
            });

            // 3. Pub/Sub 메시지 발행 (트랜잭션 외부에서 수행)
            publishInvalidationMessage(userId);

            // 4. 현재 서버의 L1 캐시도 즉시 업데이트
            Cache cache = localCacheManager.getCache(USER_CACHE);
            if (cache != null) {
                cache.put(userId, profile);
            }

            log.debug("캐시 업데이트 및 Pub/Sub 발행 완료 - userId: {}", userId);

        } catch (RedisConnectionFailureException e) {
            log.error("Redis 연결 실패로 캐시 업데이트 불가", e);
            throw e;
        }
    }

    /**
     * 캐시 무효화 메시지 발행
     */
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

        } catch (Exception e) {
            log.error("캐시 무효화 메시지 발행 실패", e);
        }
    }

    /**
     * 락 없이 단순 조회 (읽기 전용)
     * 쓰기 중인 데이터를 읽을 수 있으나, 대부분의 경우 문제없음
     */
    public UserProfile getUserProfile(String userId) {
        // L1 조회
        Cache cache = localCacheManager.getCache(USER_CACHE);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(userId);
            if (wrapper != null) {
                log.debug("L1 캐시 HIT - userId: {}", userId);
                return (UserProfile) wrapper.get();
            }
        }

        // L2 조회
        try {
            String redisKey = REDIS_USER_KEY_PREFIX + userId;
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value != null) {
                log.debug("L2 캐시 HIT - userId: {}", userId);
                UserProfile profile = objectMapper.convertValue(value, UserProfile.class);

                // L1에 저장
                if (cache != null) {
                    cache.put(userId, profile);
                }
                return profile;
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 조회 불가: {}", e.getMessage());
        }

        log.debug("캐시 MISS - userId: {}", userId);
        return null;
    }

    /**
     * 락을 사용한 조회 (강한 정합성이 필요한 경우)
     * 쓰기 작업 중에는 대기 후 조회
     */
    public UserProfile getUserProfileWithLock(String userId) {
        // RedissonClient가 없으면 락 없이 조회
        if (redissonClient == null) {
            return getUserProfile(userId);
        }

        String lockKey = LOCK_KEY_PREFIX + userId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 짧은 시간만 대기 (쓰기 작업 완료 대기)
            if (lock.tryLock(1, 3, TimeUnit.SECONDS)) {
                try {
                    return getUserProfile(userId);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                // 락 획득 실패 시에도 조회 진행 (가용성 우선)
                log.debug("락 획득 실패, 락 없이 조회 진행 - userId: {}", userId);
                return getUserProfile(userId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("락 대기 중 인터럽트 발생, 락 없이 조회 진행");
            return getUserProfile(userId);
        }
    }
}
