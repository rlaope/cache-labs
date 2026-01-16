package khope.cache.service;

import khope.cache.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * L1 (로컬 Caffeine) + L2 (Redis) 이중 캐시 서비스
 *
 * 캐시 조회 순서:
 * 1. L1 (로컬 캐시) 조회 → HIT 시 바로 반환
 * 2. L1 MISS → L2 (Redis) 조회 → HIT 시 L1에 저장 후 반환
 * 3. L2 MISS → DB 조회 → L1, L2 모두에 저장 후 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoLevelCacheService {

    private final CacheManager localCacheManager;
    private final CacheManager redisCacheManager;

    /**
     * 이중 캐시에서 값을 조회하거나, 없으면 loader를 실행하여 캐시에 저장
     */
    public <T> T getOrLoad(String cacheName, String key, Class<T> type, Supplier<T> loader) {
        // 1. L1 로컬 캐시 조회
        T value = getFromLocalCache(cacheName, key, type);
        if (value != null) {
            log.debug("L1 캐시 HIT - cacheName: {}, key: {}", cacheName, key);
            return value;
        }
        log.debug("L1 캐시 MISS - cacheName: {}, key: {}", cacheName, key);

        // 2. L2 Redis 캐시 조회
        value = getFromRedisCache(cacheName, key, type);
        if (value != null) {
            log.debug("L2 캐시 HIT - cacheName: {}, key: {}", cacheName, key);
            // L1에 저장
            putToLocalCache(cacheName, key, value);
            return value;
        }
        log.debug("L2 캐시 MISS - cacheName: {}, key: {}", cacheName, key);

        // 3. DB에서 조회
        value = loader.get();
        if (value != null) {
            log.debug("DB 조회 후 캐시 저장 - cacheName: {}, key: {}", cacheName, key);
            put(cacheName, key, value);
        }

        return value;
    }

    /**
     * 이중 캐시에 값을 저장
     */
    public void put(String cacheName, String key, Object value) {
        putToLocalCache(cacheName, key, value);
        putToRedisCache(cacheName, key, value);
    }

    /**
     * 이중 캐시에서 값을 삭제
     */
    public void evict(String cacheName, String key) {
        evictFromLocalCache(cacheName, key);
        evictFromRedisCache(cacheName, key);
    }

    /**
     * 이중 캐시 전체 삭제
     */
    public void evictAll(String cacheName) {
        Optional.ofNullable(localCacheManager.getCache(cacheName))
                .ifPresent(Cache::clear);

        try {
            Optional.ofNullable(redisCacheManager.getCache(cacheName))
                    .ifPresent(Cache::clear);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 캐시 전체 삭제 불가: {}", e.getMessage());
        }
    }

    private <T> T getFromLocalCache(String cacheName, String key, Class<T> type) {
        Cache cache = localCacheManager.getCache(cacheName);
        if (cache == null) {
            return null;
        }
        Cache.ValueWrapper wrapper = cache.get(key);
        if (wrapper == null) {
            return null;
        }
        return type.cast(wrapper.get());
    }

    private <T> T getFromRedisCache(String cacheName, String key, Class<T> type) {
        try {
            Cache cache = redisCacheManager.getCache(cacheName);
            if (cache == null) {
                return null;
            }
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper == null) {
                return null;
            }
            return type.cast(wrapper.get());
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 조회 불가: {}", e.getMessage());
            return null;
        }
    }

    private void putToLocalCache(String cacheName, String key, Object value) {
        Cache cache = localCacheManager.getCache(cacheName);
        if (cache != null) {
            cache.put(key, value);
        }
    }

    private void putToRedisCache(String cacheName, String key, Object value) {
        try {
            Cache cache = redisCacheManager.getCache(cacheName);
            if (cache != null) {
                cache.put(key, value);
            }
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 저장 불가: {}", e.getMessage());
        }
    }

    private void evictFromLocalCache(String cacheName, String key) {
        Optional.ofNullable(localCacheManager.getCache(cacheName))
                .ifPresent(cache -> cache.evict(key));
    }

    private void evictFromRedisCache(String cacheName, String key) {
        try {
            Optional.ofNullable(redisCacheManager.getCache(cacheName))
                    .ifPresent(cache -> cache.evict(key));
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 L2 캐시 삭제 불가: {}", e.getMessage());
        }
    }
}