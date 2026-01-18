package khope.cache.config;

import khope.cache.pubsub.CacheMessageSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Slf4j
@Configuration
public class RedisConfig {

    @Value("${cache.invalidation-topic:cache-invalidation}")
    private String cacheInvalidationTopic;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redis Pub/Sub 메시지 리스너 컨테이너
     * 캐시 무효화 메시지를 수신하기 위한 설정
     * cache.pubsub.enabled=true 일 때만 활성화
     */
    @Bean
    @ConditionalOnProperty(name = "cache.pubsub.enabled", havingValue = "true", matchIfMissing = false)
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {
        log.info("Redis Pub/Sub 메시지 리스너 컨테이너 초기화");
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new PatternTopic(cacheInvalidationTopic));
        return container;
    }

    /**
     * 메시지 리스너 어댑터
     * CacheMessageSubscriber의 onMessage 메서드를 호출
     */
    @Bean
    @ConditionalOnProperty(name = "cache.pubsub.enabled", havingValue = "true", matchIfMissing = false)
    public MessageListenerAdapter listenerAdapter(CacheMessageSubscriber subscriber) {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }
}
