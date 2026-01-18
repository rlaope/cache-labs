package khope.cache.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LOCAL_CACHE_MANAGER = "localCacheManager";
    public static final String REDIS_CACHE_MANAGER = "redisCacheManager";

    // 캐시 이름 상수
    public static final String STATIC_CACHE = "staticCache";
    public static final String USER_CACHE = "userCache";
    public static final String RESERVATION_CACHE = "reservationCache";

    // Static 캐시 설정
    @Value("${cache.static.local.expire-seconds:3600}")
    private long staticLocalExpireSeconds;

    @Value("${cache.static.local.maximum-size:500}")
    private long staticLocalMaximumSize;

    @Value("${cache.static.redis.expire-seconds:7200}")
    private long staticRedisExpireSeconds;

    // User 캐시 설정
    @Value("${cache.user.local.expire-seconds:60}")
    private long userLocalExpireSeconds;

    @Value("${cache.user.local.maximum-size:10000}")
    private long userLocalMaximumSize;

    @Value("${cache.user.redis.expire-seconds:300}")
    private long userRedisExpireSeconds;

    // 기존 설정 (하위 호환)
    @Value("${cache.local.expire-seconds:60}")
    private long localExpireSeconds;

    @Value("${cache.local.maximum-size:1000}")
    private long localMaximumSize;

    @Value("${cache.redis.expire-seconds:300}")
    private long redisExpireSeconds;

    /**
     * L1 캐시 - Caffeine (로컬 캐시)
     * Static/User 데이터별 다른 TTL 적용
     */
    @Bean(LOCAL_CACHE_MANAGER)
    @Primary
    public CacheManager localCacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        // Static 캐시: 긴 TTL (전역 데이터용)
        CaffeineCache staticCache = new CaffeineCache(STATIC_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(staticLocalExpireSeconds, TimeUnit.SECONDS)
                        .maximumSize(staticLocalMaximumSize)
                        .recordStats()
                        .build());

        // User 캐시: 짧은 TTL (유저별 데이터용)
        CaffeineCache userCache = new CaffeineCache(USER_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(userLocalExpireSeconds, TimeUnit.SECONDS)
                        .maximumSize(userLocalMaximumSize)
                        .recordStats()
                        .build());

        // 기존 Reservation 캐시 (하위 호환)
        CaffeineCache reservationCache = new CaffeineCache(RESERVATION_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(localExpireSeconds, TimeUnit.SECONDS)
                        .maximumSize(localMaximumSize)
                        .recordStats()
                        .build());

        cacheManager.setCaches(Arrays.asList(staticCache, userCache, reservationCache));
        return cacheManager;
    }

    /**
     * L2 캐시 - Redis (분산 캐시)
     * Static/User 데이터별 다른 TTL 적용
     */
    @Bean(REDIS_CACHE_MANAGER)
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        // 기본 설정
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(redisExpireSeconds))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        // Static 캐시 설정: 긴 TTL
        RedisCacheConfiguration staticConfig = defaultConfig
                .entryTtl(Duration.ofSeconds(staticRedisExpireSeconds));

        // User 캐시 설정: 짧은 TTL
        RedisCacheConfiguration userConfig = defaultConfig
                .entryTtl(Duration.ofSeconds(userRedisExpireSeconds));

        // 캐시별 설정 매핑
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put(STATIC_CACHE, staticConfig);
        cacheConfigurations.put(USER_CACHE, userConfig);
        cacheConfigurations.put(RESERVATION_CACHE, defaultConfig);

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
