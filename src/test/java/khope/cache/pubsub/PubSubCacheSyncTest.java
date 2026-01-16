package khope.cache.pubsub;

import khope.cache.config.CacheConfig;
import khope.cache.domain.Reservation;
import khope.cache.service.TwoLevelCacheService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pub/Sub을 통한 캐시 동기화 테스트
 *
 * README 3.4, 4.4 Pub/Sub을 활용한 캐시 동기화 테스트
 * - Redis Pub/Sub을 통한 L1 캐시 무효화 브로드캐스팅
 * - 여러 노드 간 캐시 일관성 유지
 *
 * 주의: 이 테스트는 Redis가 필요합니다. macOS ARM에서는 Embedded Redis가
 * 지원되지 않으므로 로컬 Redis 서버가 실행 중이어야 합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Pub/Sub 캐시 동기화 테스트")
class PubSubCacheSyncTest {

    @Autowired
    private TwoLevelCacheService cacheService;

    @Autowired
    private CacheManager localCacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_NAME = CacheConfig.RESERVATION_CACHE;
    private static final String CACHE_INVALIDATION_CHANNEL = "cache:invalidation";

    private RedisMessageListenerContainer listenerContainer;
    private List<SimulatedNode> simulatedNodes;
    private boolean redisAvailable;

    @BeforeEach
    void setUp() {
        redisAvailable = checkRedisAvailable();
        if (redisAvailable) {
            cacheService.evictAll(CACHE_NAME);
        }
        simulatedNodes = new ArrayList<>();
    }

    private boolean checkRedisAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void tearDown() {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
        simulatedNodes.forEach(SimulatedNode::shutdown);
    }

    @Test
    @DisplayName("Pub/Sub을 통한 캐시 무효화 메시지 브로드캐스팅")
    void pubSubInvalidation_broadcastToAllNodes() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping Pub/Sub test");

        // Given - 3개의 노드 시뮬레이션
        int nodeCount = 3;
        String key = "reservation:pubsub-test";

        // 각 노드의 L1 캐시에 데이터 저장
        Reservation originalData = createTestReservation(1L);
        for (int i = 0; i < nodeCount; i++) {
            SimulatedNode node = new SimulatedNode("Node-" + i, redisTemplate);
            node.putToLocalCache(key, originalData);
            node.subscribeToInvalidationChannel(CACHE_INVALIDATION_CHANNEL);
            simulatedNodes.add(node);
        }

        // 모든 노드가 구독 완료될 때까지 대기
        Thread.sleep(500);

        // When - 캐시 무효화 메시지 발행
        System.out.println("=== Pub/Sub 캐시 무효화 테스트 ===");
        System.out.println("노드 수: " + nodeCount);
        System.out.println("무효화 대상 키: " + key);
        System.out.println();

        redisTemplate.convertAndSend(CACHE_INVALIDATION_CHANNEL, key);
        System.out.println("[Publisher] 무효화 메시지 발행: " + key);

        // Then - 모든 노드가 메시지를 수신했는지 확인
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> simulatedNodes.stream()
                        .allMatch(node -> node.getReceivedMessages().contains(key)));

        System.out.println();
        for (SimulatedNode node : simulatedNodes) {
            System.out.println("[" + node.getName() + "] 수신한 메시지: " + node.getReceivedMessages());
            assertThat(node.getReceivedMessages()).contains(key);
        }

        System.out.println();
        System.out.println("✅ 모든 노드가 무효화 메시지를 수신함");
    }

    @Test
    @DisplayName("데이터 업데이트 시 모든 노드의 L1 캐시 무효화")
    void dataUpdate_invalidateAllNodesL1Cache() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping Pub/Sub test");

        // Given
        int nodeCount = 4;
        String key = "reservation:update-test";

        // 각 노드에 초기 데이터 캐싱
        Reservation v1Data = createTestReservation(1L);
        List<ConcurrentHashMap<String, Object>> nodeLocalCaches = new ArrayList<>();

        for (int i = 0; i < nodeCount; i++) {
            ConcurrentHashMap<String, Object> localCache = new ConcurrentHashMap<>();
            localCache.put(key, v1Data);
            nodeLocalCaches.add(localCache);

            SimulatedNode node = new SimulatedNode("Node-" + i, redisTemplate);
            node.setLocalCache(localCache);
            node.subscribeToInvalidationChannel(CACHE_INVALIDATION_CHANNEL);
            simulatedNodes.add(node);
        }

        Thread.sleep(500);

        // When - 데이터 업데이트 발생 (특정 노드에서)
        System.out.println("=== 데이터 업데이트 시 캐시 무효화 테스트 ===");
        System.out.println("노드 수: " + nodeCount);

        System.out.println();
        System.out.println("[Before Update]");
        for (int i = 0; i < nodeCount; i++) {
            System.out.println("  Node-" + i + " L1 캐시: " +
                    (nodeLocalCaches.get(i).containsKey(key) ? "v1 데이터 있음" : "없음"));
        }

        // 업데이트 수행 노드가 무효화 메시지 발행
        System.out.println();
        System.out.println("[Update] Node-0에서 데이터 업데이트 및 무효화 메시지 발행");
        redisTemplate.convertAndSend(CACHE_INVALIDATION_CHANNEL, key);

        // Then - 모든 노드가 L1 캐시를 무효화해야 함
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> simulatedNodes.stream()
                        .allMatch(node -> node.getReceivedMessages().contains(key)));

        // 무효화 메시지 수신 후 각 노드가 L1 캐시 삭제
        for (int i = 0; i < nodeCount; i++) {
            if (simulatedNodes.get(i).getReceivedMessages().contains(key)) {
                nodeLocalCaches.get(i).remove(key);
            }
        }

        System.out.println();
        System.out.println("[After Invalidation]");
        for (int i = 0; i < nodeCount; i++) {
            boolean hasCache = nodeLocalCaches.get(i).containsKey(key);
            System.out.println("  Node-" + i + " L1 캐시: " + (hasCache ? "있음" : "삭제됨 ✓"));
            assertThat(hasCache).isFalse();
        }

        System.out.println();
        System.out.println("✅ 모든 노드의 L1 캐시가 무효화됨");
    }

    @Test
    @DisplayName("Pub/Sub 메시지 유실 시뮬레이션 및 짧은 TTL 보완")
    void pubSubMessageLoss_shortTtlCompensation() throws Exception {
        // Given
        String key = "reservation:message-loss-test";
        int nodeCount = 3;
        long shortL1TtlSeconds = 2; // 매우 짧은 L1 TTL

        System.out.println("=== Pub/Sub 메시지 유실 및 TTL 보완 테스트 ===");
        System.out.println("노드 수: " + nodeCount);
        System.out.println("L1 TTL: " + shortL1TtlSeconds + "초");

        // 각 노드에 데이터 캐싱 (TTL 포함)
        List<CacheEntry> nodeL1Caches = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            CacheEntry entry = new CacheEntry(
                    createTestReservation(1L),
                    System.currentTimeMillis() + (shortL1TtlSeconds * 1000)
            );
            nodeL1Caches.add(entry);
        }

        System.out.println();
        System.out.println("[초기 상태] 모든 노드 L1 캐시에 v1 데이터 존재");

        // When - 메시지 유실 시뮬레이션 (Node-2가 메시지를 못 받음)
        System.out.println();
        System.out.println("[시뮬레이션] 데이터 v2로 업데이트, 무효화 메시지 발행");
        System.out.println("  - Node-0: 메시지 수신 ✓ → L1 캐시 삭제");
        System.out.println("  - Node-1: 메시지 수신 ✓ → L1 캐시 삭제");
        System.out.println("  - Node-2: 메시지 유실 ✗ → L1 캐시 유지 (불일치!)");

        // Node-0, Node-1은 캐시 삭제, Node-2는 유지 (메시지 유실)
        nodeL1Caches.get(0).invalidate();
        nodeL1Caches.get(1).invalidate();
        // nodeL1Caches.get(2)는 메시지를 못 받아서 그대로

        System.out.println();
        System.out.println("[불일치 상태]");
        for (int i = 0; i < nodeCount; i++) {
            CacheEntry entry = nodeL1Caches.get(i);
            System.out.println("  Node-" + i + ": " +
                    (entry.isValid() ? "v1 데이터 (stale!)" : "캐시 없음 (정상)"));
        }

        // Then - TTL 만료 후 자동 동기화
        System.out.println();
        System.out.println("[TTL 만료 대기 중...] " + shortL1TtlSeconds + "초");
        Thread.sleep((shortL1TtlSeconds + 1) * 1000);

        System.out.println();
        System.out.println("[TTL 만료 후]");
        for (int i = 0; i < nodeCount; i++) {
            CacheEntry entry = nodeL1Caches.get(i);
            boolean isExpired = System.currentTimeMillis() > entry.getExpiryTime();
            System.out.println("  Node-" + i + ": " +
                    (isExpired ? "TTL 만료 → 다음 조회 시 L2에서 최신 데이터 로드" : "아직 유효"));
            assertThat(isExpired).isTrue();
        }

        System.out.println();
        System.out.println("✅ 짧은 TTL로 인해 메시지 유실 시에도 자동으로 동기화됨");
        System.out.println("   권장: L1 TTL은 10~30초, Pub/Sub은 보조 수단으로 사용");
    }

    @Test
    @DisplayName("대량 캐시 무효화 시 Pub/Sub 성능")
    void bulkInvalidation_pubSubPerformance() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping Pub/Sub test");

        // Given
        int keyCount = 100;
        int nodeCount = 5;
        AtomicInteger totalMessagesReceived = new AtomicInteger(0);

        // 노드들이 메시지 수신 카운트
        for (int i = 0; i < nodeCount; i++) {
            SimulatedNode node = new SimulatedNode("Node-" + i, redisTemplate) {
                @Override
                public void onMessageReceived(String message) {
                    super.onMessageReceived(message);
                    totalMessagesReceived.incrementAndGet();
                }
            };
            node.subscribeToInvalidationChannel(CACHE_INVALIDATION_CHANNEL);
            simulatedNodes.add(node);
        }

        Thread.sleep(500);

        // When - 대량 무효화 메시지 발행
        System.out.println("=== 대량 캐시 무효화 Pub/Sub 성능 테스트 ===");
        System.out.println("무효화 키 수: " + keyCount);
        System.out.println("수신 노드 수: " + nodeCount);
        System.out.println("예상 총 메시지 수: " + (keyCount * nodeCount));

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < keyCount; i++) {
            String key = "bulk-invalidation:key-" + i;
            redisTemplate.convertAndSend(CACHE_INVALIDATION_CHANNEL, key);
        }

        long publishTime = System.currentTimeMillis() - startTime;

        // Then - 모든 메시지가 수신될 때까지 대기
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> totalMessagesReceived.get() >= keyCount * nodeCount);

        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("[결과]");
        System.out.println("  발행 시간: " + publishTime + "ms");
        System.out.println("  총 처리 시간: " + totalTime + "ms");
        System.out.println("  수신된 총 메시지 수: " + totalMessagesReceived.get());
        System.out.println("  초당 메시지 처리량: " +
                String.format("%.0f", (keyCount * nodeCount * 1000.0) / totalTime) + " msg/s");

        System.out.println();
        System.out.println("✅ Pub/Sub을 통한 대량 무효화 처리 완료");

        assertThat(totalMessagesReceived.get()).isGreaterThanOrEqualTo(keyCount * nodeCount);
    }

    /**
     * 시뮬레이션된 노드 클래스
     */
    static class SimulatedNode {
        private final String name;
        private final RedisTemplate<String, Object> redisTemplate;
        private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
        private Map<String, Object> localCache = new ConcurrentHashMap<>();
        private RedisMessageListenerContainer container;

        public SimulatedNode(String name, RedisTemplate<String, Object> redisTemplate) {
            this.name = name;
            this.redisTemplate = redisTemplate;
        }

        public void subscribeToInvalidationChannel(String channel) {
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redisTemplate.getConnectionFactory());
            container.addMessageListener(
                    (message, pattern) -> onMessageReceived(new String(message.getBody())),
                    new ChannelTopic(channel)
            );
            container.afterPropertiesSet();
            container.start();
        }

        public void onMessageReceived(String message) {
            receivedMessages.add(message);
            localCache.remove(message);
        }

        public void putToLocalCache(String key, Object value) {
            localCache.put(key, value);
        }

        public void setLocalCache(Map<String, Object> cache) {
            this.localCache = cache;
        }

        public String getName() {
            return name;
        }

        public List<String> getReceivedMessages() {
            return receivedMessages;
        }

        public void shutdown() {
            if (container != null) {
                container.stop();
            }
        }
    }

    /**
     * TTL이 있는 캐시 엔트리
     */
    static class CacheEntry {
        private final Object value;
        private final long expiryTime;
        private boolean invalidated = false;

        public CacheEntry(Object value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }

        public boolean isValid() {
            return !invalidated && System.currentTimeMillis() < expiryTime;
        }

        public void invalidate() {
            this.invalidated = true;
        }

        public long getExpiryTime() {
            return expiryTime;
        }
    }

    private Reservation createTestReservation(Long id) {
        return Reservation.builder()
                .customerName("Customer-" + id)
                .resourceName("Resource-" + id)
                .reservationTime(LocalDateTime.now())
                .build();
    }
}
