package khope.cache.stampede;

import khope.cache.config.CacheConfig;
import khope.cache.domain.Reservation;
import khope.cache.service.TwoLevelCacheService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache Stampede (Thundering Herd) 테스트
 *
 * README 3.3 Cache Stampede 방지 테스트
 * - 캐시 만료 시 동시 다발적 DB 접근 문제 시뮬레이션
 * - Jitter, Distributed Lock, PER 등의 해결책 검증
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Cache Stampede 테스트")
class CacheStampedeTest {

    @Autowired
    private TwoLevelCacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_NAME = CacheConfig.RESERVATION_CACHE;

    @BeforeEach
    void setUp() {
        cacheService.evictAll(CACHE_NAME);
    }

    @Test
    @DisplayName("캐시 만료 시 Stampede 현상 시뮬레이션 (문제 상황)")
    void cacheExpiry_stampede_problemSimulation() throws Exception {
        // Given
        String key = "hot-product:12345";
        int concurrentRequests = 100;
        AtomicInteger dbCallCount = new AtomicInteger(0);
        AtomicLong totalDbTime = new AtomicLong(0);

        // 초기 캐시 저장 후 즉시 만료 시뮬레이션
        cacheService.evict(CACHE_NAME, key);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRequests);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

        // When - 캐시 만료 직후 동시에 많은 요청 발생
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();

                    cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                        int callNum = dbCallCount.incrementAndGet();
                        long dbStart = System.currentTimeMillis();

                        // DB 조회 시뮬레이션 (100ms 소요)
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        totalDbTime.addAndGet(System.currentTimeMillis() - dbStart);
                        return createTestReservation(12345L);
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        System.out.println("=== Cache Stampede 문제 상황 시뮬레이션 ===");
        System.out.println("동시 요청 수: " + concurrentRequests);
        System.out.println("DB 호출 횟수: " + dbCallCount.get());
        System.out.println("총 DB 처리 시간: " + totalDbTime.get() + "ms");
        System.out.println("전체 처리 시간: " + totalTime + "ms");
        System.out.println();
        System.out.println("⚠️ 문제: DB 호출이 " + dbCallCount.get() + "회 발생!");
        System.out.println("   이상적인 경우 1회만 호출되어야 함");

        // Stampede 발생 확인 - 이상적으로는 1이어야 하지만 실제로는 더 많이 호출됨
        // 이 테스트는 문제 상황을 보여주기 위한 것
        assertThat(dbCallCount.get()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Distributed Lock을 사용한 Stampede 방지")
    void distributedLock_preventStampede() throws Exception {
        // Given
        String key = "hot-product:lock-test";
        String lockKey = "lock:" + key;
        int concurrentRequests = 50;
        AtomicInteger dbCallCount = new AtomicInteger(0);

        cacheService.evict(CACHE_NAME, key);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentRequests);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

        // When - Lock을 사용한 조회
        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    getWithLock(key, lockKey, dbCallCount);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        System.out.println("=== Distributed Lock으로 Stampede 방지 ===");
        System.out.println("동시 요청 수: " + concurrentRequests);
        System.out.println("DB 호출 횟수: " + dbCallCount.get());
        System.out.println("✅ Lock을 통해 DB 호출이 최소화됨");

        // Lock으로 인해 DB 호출이 크게 줄어들어야 함
        assertThat(dbCallCount.get()).isLessThanOrEqualTo(5); // 최소한으로 유지
    }

    @Test
    @DisplayName("TTL Jitter를 사용한 동시 만료 방지")
    void ttlJitter_preventSimultaneousExpiry() {
        // Given
        int cacheCount = 100;
        long baseTtlSeconds = 300; // 5분
        double jitterPercent = 0.1; // 10%

        List<Long> ttlValues = new ArrayList<>();
        Random random = new Random();

        // When - Jitter가 적용된 TTL 생성
        for (int i = 0; i < cacheCount; i++) {
            long jitter = (long) (baseTtlSeconds * jitterPercent * random.nextDouble());
            long actualTtl = baseTtlSeconds + jitter;
            ttlValues.add(actualTtl);
        }

        // Then - TTL 분포 분석
        long minTtl = ttlValues.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxTtl = ttlValues.stream().mapToLong(Long::longValue).max().orElse(0);
        double avgTtl = ttlValues.stream().mapToLong(Long::longValue).average().orElse(0);

        System.out.println("=== TTL Jitter 분포 분석 ===");
        System.out.println("캐시 항목 수: " + cacheCount);
        System.out.println("기본 TTL: " + baseTtlSeconds + "초");
        System.out.println("Jitter 범위: " + (jitterPercent * 100) + "%");
        System.out.println("최소 TTL: " + minTtl + "초");
        System.out.println("최대 TTL: " + maxTtl + "초");
        System.out.println("평균 TTL: " + String.format("%.2f", avgTtl) + "초");
        System.out.println("TTL 분산 범위: " + (maxTtl - minTtl) + "초");
        System.out.println();
        System.out.println("✅ TTL이 " + (maxTtl - minTtl) + "초에 걸쳐 분산됨");
        System.out.println("   → 동시 만료로 인한 Stampede 위험 감소");

        // TTL이 충분히 분산되었는지 확인
        assertThat(maxTtl - minTtl).isGreaterThan(0);
        assertThat(minTtl).isGreaterThanOrEqualTo(baseTtlSeconds);
        assertThat(maxTtl).isLessThanOrEqualTo((long) (baseTtlSeconds * (1 + jitterPercent)));
    }

    @Test
    @DisplayName("PER(Probabilistic Early Recomputation) 시뮬레이션")
    void probabilisticEarlyRecomputation_simulation() {
        // Given
        String key = "per-test:product";
        long ttlSeconds = 300; // 5분
        long delta = 10; // 캐시 계산에 걸리는 시간 (초)
        double beta = 1.0; // 확률 조절 파라미터

        cacheService.put(CACHE_NAME, key, createTestReservation(1L));

        // When - PER 알고리즘 시뮬레이션
        System.out.println("=== PER (Probabilistic Early Recomputation) 시뮬레이션 ===");
        System.out.println("TTL: " + ttlSeconds + "초");
        System.out.println("Delta (계산 시간): " + delta + "초");
        System.out.println("Beta: " + beta);
        System.out.println();

        Random random = new Random();
        int totalChecks = 100;
        int earlyRefreshCount = 0;

        for (int i = 0; i < totalChecks; i++) {
            // 남은 시간 시뮬레이션 (만료 30초 전부터 체크)
            long remainingTime = 30 - (i % 30);

            // PER 공식: currentTime - delta * beta * ln(random()) > expiry
            // 간소화: remainingTime < delta * beta * -ln(random())
            double threshold = delta * beta * -Math.log(random.nextDouble());

            if (remainingTime < threshold) {
                earlyRefreshCount++;
                System.out.println("  [" + (i + 1) + "] 남은 시간: " + remainingTime + "초, 임계값: " +
                        String.format("%.2f", threshold) + " → 조기 갱신 트리거!");
            }
        }

        System.out.println();
        System.out.println("총 체크 횟수: " + totalChecks);
        System.out.println("조기 갱신 트리거 횟수: " + earlyRefreshCount);
        System.out.println("조기 갱신 비율: " + String.format("%.2f%%", (earlyRefreshCount * 100.0) / totalChecks));
        System.out.println();
        System.out.println("✅ PER은 만료 직전에 확률적으로 캐시를 갱신하여 Stampede 방지");

        // PER이 어느 정도 작동하는지 확인
        assertThat(earlyRefreshCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("Hot Key 시뮬레이션 - 특정 키에 트래픽 집중")
    void hotKey_trafficConcentration() throws Exception {
        // Given
        String hotKey = "popular-product:black-friday";
        String[] normalKeys = {"product:1", "product:2", "product:3", "product:4", "product:5"};

        int totalRequests = 1000;
        double hotKeyRatio = 0.8; // 80%가 hot key 요청

        AtomicInteger hotKeyDbCalls = new AtomicInteger(0);
        AtomicInteger normalKeyDbCalls = new AtomicInteger(0);
        AtomicInteger hotKeyHits = new AtomicInteger(0);
        AtomicInteger normalKeyHits = new AtomicInteger(0);

        // 캐시 초기화
        cacheService.evict(CACHE_NAME, hotKey);
        for (String key : normalKeys) {
            cacheService.evict(CACHE_NAME, key);
        }

        Random random = new Random();
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        // When
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    boolean isHotKeyRequest = random.nextDouble() < hotKeyRatio;

                    if (isHotKeyRequest) {
                        Reservation result = cacheService.getOrLoad(CACHE_NAME, hotKey, Reservation.class, () -> {
                            hotKeyDbCalls.incrementAndGet();
                            simulateDbDelay(50);
                            return createTestReservation(9999L);
                        });
                        if (result != null) hotKeyHits.incrementAndGet();
                    } else {
                        String randomKey = normalKeys[random.nextInt(normalKeys.length)];
                        Reservation result = cacheService.getOrLoad(CACHE_NAME, randomKey, Reservation.class, () -> {
                            normalKeyDbCalls.incrementAndGet();
                            simulateDbDelay(50);
                            return createTestReservation(random.nextLong());
                        });
                        if (result != null) normalKeyHits.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        System.out.println("=== Hot Key 트래픽 집중 시뮬레이션 ===");
        System.out.println("총 요청 수: " + totalRequests);
        System.out.println("Hot Key 비율: " + (hotKeyRatio * 100) + "%");
        System.out.println();
        System.out.println("[Hot Key]");
        System.out.println("  요청 수: ~" + (int) (totalRequests * hotKeyRatio));
        System.out.println("  DB 호출: " + hotKeyDbCalls.get());
        System.out.println("  캐시 HIT: " + hotKeyHits.get());
        System.out.println();
        System.out.println("[Normal Keys]");
        System.out.println("  요청 수: ~" + (int) (totalRequests * (1 - hotKeyRatio)));
        System.out.println("  DB 호출: " + normalKeyDbCalls.get());
        System.out.println("  캐시 HIT: " + normalKeyHits.get());
        System.out.println();
        System.out.println("✅ Hot Key에 대한 캐시 효과가 높음 (DB 호출 최소화)");

        // Hot Key에 대한 캐시가 효과적인지 확인
        assertThat(hotKeyDbCalls.get()).isLessThan(10);
    }

    /**
     * Distributed Lock을 사용한 캐시 조회
     */
    private Reservation getWithLock(String key, String lockKey, AtomicInteger dbCallCount) {
        // 먼저 캐시 확인
        Reservation cached = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> null);
        if (cached != null) {
            return cached;
        }

        // Lock 획득 시도
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", 5, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(acquired)) {
            try {
                // Lock 획득 성공 - DB 조회 및 캐시 저장
                return cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                    dbCallCount.incrementAndGet();
                    simulateDbDelay(100);
                    return createTestReservation(1L);
                });
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            // Lock 획득 실패 - 잠시 대기 후 캐시 재확인
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                dbCallCount.incrementAndGet();
                return createTestReservation(1L);
            });
        }
    }

    private void simulateDbDelay(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
