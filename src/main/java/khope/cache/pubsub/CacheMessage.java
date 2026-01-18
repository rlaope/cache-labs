package khope.cache.pubsub;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 캐시 무효화 메시지
 * Redis Pub/Sub을 통해 모든 서버에 캐시 무효화를 전파
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CacheMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 캐시 타입 (STATIC_CACHE, USER_CACHE 등)
     */
    private String cacheName;

    /**
     * 무효화할 캐시 키
     */
    private String key;

    /**
     * 메시지를 발행한 서버 ID (자기 자신은 스킵하기 위해 사용)
     */
    private String originServerId;

    /**
     * 전체 캐시 무효화 여부
     */
    private boolean evictAll;
}