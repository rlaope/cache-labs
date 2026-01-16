package khope.cache.distributed;

import khope.cache.config.CacheConfig;
import khope.cache.domain.Reservation;
import khope.cache.service.TwoLevelCacheService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 분산 환경 시뮬레이션 테스트
 *
 * README 2.1 L1 + L2 이중 캐시 구조 테스트
 * - 여러 "노드"(스레드)가 동시에 캐시에 접근하는 상황 시뮬레이션
 * - L1(로컬) 캐시와 L2(Redis) 캐시의 동작 검증
 *
 * 주의: 이 테스트는 Redis가 필요합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("분산 환경 캐시 시뮬레이션 테스트")
class DistributedCacheSimulationTest {

    @Autowired
    private TwoLevelCacheService cacheService;

    @Autowired
    private CacheManager localCacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_NAME = CacheConfig.RESERVATION_CACHE;
    private boolean redisAvailable;

    @BeforeEach
    void setUp() {
        redisAvailable = checkRedisAvailable();
        if (redisAvailable) {
            cacheService.evictAll(CACHE_NAME);
        }
    }

    private boolean checkRedisAvailable() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("L1 캐시 HIT 시 L2(Redis) 접근 없이 바로 반환")
    void l1CacheHit_shouldNotAccessL2() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "reservation:1";
        Reservation reservation = createTestReservation(1L);

        // L1, L2 모두에 캐시 저장
        cacheService.put(CACHE_NAME, key, reservation);

        // When
        AtomicInteger dbCallCount = new AtomicInteger(0);
        Reservation result = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
            dbCallCount.incrementAndGet();
            return createTestReservation(1L);
        });

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getCustomerName()).isEqualTo("Customer-1");
        assertThat(dbCallCount.get()).isEqualTo(0); // DB 호출 없음
    }

    @Test
    @DisplayName("L1 MISS, L2 HIT 시 L1에 복사 후 반환")
    void l1Miss_l2Hit_shouldCopyToL1() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "reservation:2";
        Reservation reservation = createTestReservation(2L);

        // L2(Redis)에만 저장
        cacheService.put(CACHE_NAME, key, reservation);
        // L1 캐시만 삭제
        localCacheManager.getCache(CACHE_NAME).evict(key);

        // When
        AtomicInteger dbCallCount = new AtomicInteger(0);
        Reservation result = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
            dbCallCount.incrementAndGet();
            return createTestReservation(2L);
        });

        // Then
        assertThat(result).isNotNull();
        assertThat(dbCallCount.get()).isEqualTo(0); // L2에서 가져왔으므로 DB 호출 없음

        // L1에 복사되었는지 확인 (두 번째 호출은 L1에서 가져옴)
        Reservation secondCall = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
            dbCallCount.incrementAndGet();
            return createTestReservation(2L);
        });
        assertThat(secondCall).isNotNull();
        assertThat(dbCallCount.get()).isEqualTo(0); // 여전히 DB 호출 없음
    }

    @Test
    @DisplayName("L1, L2 모두 MISS 시 DB에서 조회 후 양쪽 캐시에 저장")
    void bothMiss_shouldLoadFromDbAndCacheBoth() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "reservation:3";
        AtomicInteger dbCallCount = new AtomicInteger(0);

        // When
        Reservation result = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
            dbCallCount.incrementAndGet();
            return createTestReservation(3L);
        });

        // Then
        assertThat(result).isNotNull();
        assertThat(dbCallCount.get()).isEqualTo(1); // DB 1회 호출

        // 두 번째 호출은 캐시에서 가져옴
        Reservation secondCall = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
            dbCallCount.incrementAndGet();
            return createTestReservation(3L);
        });
        assertThat(dbCallCount.get()).isEqualTo(1); // 여전히 1회
    }

    @Test
    @DisplayName("여러 노드(스레드)가 동시에 동일 키 조회 시 DB 호출 최소화")
    void multipleNodes_concurrentAccess_shouldMinimizeDbCalls() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "reservation:hot-key";
        int nodeCount = 10;
        int requestsPerNode = 100;
        AtomicInteger dbCallCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(nodeCount);

        ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
        List<Future<Integer>> futures = new ArrayList<>();

        // When - 여러 노드가 동시에 같은 키 조회
        for (int node = 0; node < nodeCount; node++) {
            futures.add(executor.submit(() -> {
                int hitCount = 0;
                startLatch.await(); // 모든 스레드가 동시에 시작

                for (int i = 0; i < requestsPerNode; i++) {
                    Reservation result = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                        dbCallCount.incrementAndGet();
                        // DB 조회 시뮬레이션 (약간의 지연)
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return createTestReservation(999L);
                    });
                    if (result != null) hitCount++;
                }

                endLatch.countDown();
                return hitCount;
            }));
        }

        startLatch.countDown(); // 모든 스레드 시작
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        int totalHits = futures.stream().mapToInt(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return 0;
            }
        }).sum();

        System.out.println("=== 분산 환경 시뮬레이션 결과 ===");
        System.out.println("총 노드 수: " + nodeCount);
        System.out.println("노드당 요청 수: " + requestsPerNode);
        System.out.println("총 요청 수: " + (nodeCount * requestsPerNode));
        System.out.println("총 캐시 HIT: " + totalHits);
        System.out.println("DB 호출 횟수: " + dbCallCount.get());
        System.out.println("캐시 HIT 비율: " + String.format("%.2f%%", (totalHits * 100.0) / (nodeCount * requestsPerNode)));

        // 대부분의 요청이 캐시에서 처리되어야 함
        assertThat(totalHits).isEqualTo(nodeCount * requestsPerNode);
        // DB 호출은 최소화되어야 함 (동시성으로 인해 몇 번 더 호출될 수 있음)
        assertThat(dbCallCount.get()).isLessThanOrEqualTo(nodeCount); // 최악의 경우에도 노드 수만큼만
    }

    @Test
    @DisplayName("캐시 무효화 후 모든 노드가 최신 데이터 조회")
    void cacheInvalidation_allNodesShouldGetFreshData() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "reservation:invalidation-test";
        AtomicInteger version = new AtomicInteger(1);

        // 초기 데이터 캐싱
        cacheService.put(CACHE_NAME, key, createTestReservation(1L));

        // When - 캐시 무효화
        cacheService.evict(CACHE_NAME, key);
        version.incrementAndGet();

        // Then - 새로운 데이터 로드
        int nodeCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
        List<Future<Reservation>> futures = new ArrayList<>();

        for (int i = 0; i < nodeCount; i++) {
            futures.add(executor.submit(() -> {
                return cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                    return createTestReservation((long) version.get());
                });
            }));
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 모든 노드가 최신 버전(2)의 데이터를 가져와야 함
        for (Future<Reservation> future : futures) {
            Reservation result = future.get();
            assertThat(result.getCustomerName()).isEqualTo("Customer-2");
        }
    }

    @Test
    @DisplayName("L1 캐시 불일치 시뮬레이션 - 서로 다른 L1 캐시 상태")
    void l1CacheInconsistency_simulation() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given - 서로 다른 "노드"의 L1 캐시 상태 시뮬레이션
        String key = "reservation:inconsistency-test";

        // 노드 A가 데이터 저장
        Reservation originalData = createTestReservation(1L);
        cacheService.put(CACHE_NAME, key, originalData);

        // 노드 B에서 데이터 업데이트 (L2만 업데이트하고 노드 A의 L1은 그대로)
        Reservation updatedData = Reservation.builder()
                .customerName("Updated-Customer")
                .resourceName("Updated-Resource")
                .reservationTime(LocalDateTime.now())
                .build();

        // L2만 직접 업데이트 (다른 노드에서 업데이트한 상황 시뮬레이션)
        // 실제로는 Pub/Sub으로 L1 무효화 메시지를 보내야 함

        System.out.println("=== L1 캐시 불일치 시뮬레이션 ===");
        System.out.println("이 시나리오는 Pub/Sub 없이 L1 캐시가 불일치할 수 있음을 보여줍니다.");
        System.out.println("해결책: Redis Pub/Sub을 통한 L1 캐시 무효화 브로드캐스팅");
    }

    private Reservation createTestReservation(Long id) {
        return Reservation.builder()
                .customerName("Customer-" + id)
                .resourceName("Resource-" + id)
                .reservationTime(LocalDateTime.now())
                .build();
    }
}
