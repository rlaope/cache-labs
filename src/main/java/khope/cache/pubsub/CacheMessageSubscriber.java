package khope.cache.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Redis Pub/Sub 메시지 구독자
 * 다른 서버에서 발행한 캐시 무효화 메시지를 수신하여 로컬 캐시를 무효화
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheMessageSubscriber {

    private final CacheManager localCacheManager;
    private final ObjectMapper objectMapper;

    /**
     * 현재 서버의 고유 ID (자기 자신이 발행한 메시지는 스킵)
     */
    private final String serverId = UUID.randomUUID().toString();

    /**
     * Redis Pub/Sub 메시지 수신 핸들러
     * MessageListenerAdapter에 의해 호출됨
     */
    public void onMessage(String message) {
        try {
            CacheMessage cacheMessage = objectMapper.readValue(message, CacheMessage.class);
            log.debug("캐시 무효화 메시지 수신: cacheName={}, key={}, originServer={}",
                    cacheMessage.getCacheName(), cacheMessage.getKey(), cacheMessage.getOriginServerId());

            // 자기 자신이 발행한 메시지는 무시 (이미 로컬 캐시 처리됨)
            // 네트워크 비용 vs 정합성 트레이드오프에서 스킵 선택
            if (serverId.equals(cacheMessage.getOriginServerId())) {
                log.debug("자기 자신이 발행한 메시지 스킵: serverId={}", serverId);
                return;
            }

            Cache cache = localCacheManager.getCache(cacheMessage.getCacheName());
            if (cache != null) {
                if (cacheMessage.isEvictAll()) {
                    cache.clear();
                    log.info("L1 캐시 전체 무효화 완료: cacheName={}", cacheMessage.getCacheName());
                } else {
                    cache.evict(cacheMessage.getKey());
                    log.info("L1 캐시 무효화 완료: cacheName={}, key={}",
                            cacheMessage.getCacheName(), cacheMessage.getKey());
                }
            } else {
                log.warn("캐시를 찾을 수 없음: cacheName={}", cacheMessage.getCacheName());
            }
        } catch (Exception e) {
            log.error("캐시 무효화 메시지 처리 실패: message={}", message, e);
        }
    }

    /**
     * 현재 서버 ID 반환 (메시지 발행 시 사용)
     */
    public String getServerId() {
        return serverId;
    }
}
