package khope.cache.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
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
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LOCAL_CACHE_MANAGER = "localCacheManager";
    public static final String REDIS_CACHE_MANAGER = "redisCacheManager";
    public static final String RESERVATION_CACHE = "reservationCache";

    @Value("${cache.local.expire-seconds:60}")
    private long localExpireSeconds;

    @Value("${cache.local.maximum-size:1000}")
    private long localMaximumSize;

    @Value("${cache.redis.expire-seconds:300}")
    private long redisExpireSeconds;

    /**
     * L1 캐시 - Caffeine (로컬 캐시)
     * 빠른 응답을 위한 인메모리 캐시
     */
    @Bean(LOCAL_CACHE_MANAGER)
    @Primary
    public CacheManager localCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(RESERVATION_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(localExpireSeconds, TimeUnit.SECONDS)
                .maximumSize(localMaximumSize)
                .recordStats());
        return cacheManager;
    }

    /**
     * L2 캐시 - Redis (분산 캐시)
     * 여러 인스턴스 간 공유를 위한 분산 캐시
     */
    @Bean(REDIS_CACHE_MANAGER)
    public CacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(redisExpireSeconds))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .build();
    }
}
