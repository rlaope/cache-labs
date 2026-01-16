package khope.cache.performance;

import khope.cache.config.CacheConfig;
import khope.cache.domain.Reservation;
import khope.cache.service.TwoLevelCacheService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 캐시 성능 테스트
 *
 * README에서 정리한 캐시 전략들의 성능 검증
 * - L1 vs L2 vs DB 응답 시간 비교
 * - 캐시 HIT 비율 측정
 * - 동시성 환경에서의 성능
 *
 * 주의: 이 테스트는 Redis가 필요합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("캐시 성능 테스트")
class CachePerformanceTest {

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
    @DisplayName("L1 캐시 vs L2 캐시 vs DB 응답 시간 비교")
    void compareResponseTimes_L1vsL2vsDB() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        String key = "perf:response-time-test";
        Reservation data = createTestReservation(1L);
        int iterations = 1000;

        // DB 응답 시간 측정 (캐시 MISS 시뮬레이션)
        List<Long> dbTimes = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            simulateDbQuery(50); // 50ms DB 지연
            dbTimes.add(System.nanoTime() - start);
        }

        // L2 (Redis) 응답 시간 측정
        cacheService.put(CACHE_NAME, key, data);
        localCacheManager.getCache(CACHE_NAME).evict(key); // L1만 삭제

        List<Long> l2Times = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            localCacheManager.getCache(CACHE_NAME).evict(key); // L1 삭제
            long start = System.nanoTime();
            cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> data);
            l2Times.add(System.nanoTime() - start);
        }

        // L1 (Local) 응답 시간 측정
        cacheService.put(CACHE_NAME, key, data);

        List<Long> l1Times = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> data);
            l1Times.add(System.nanoTime() - start);
        }

        // Then - 결과 출력
        System.out.println("=== 응답 시간 비교 (ns) ===");
        System.out.println("반복 횟수: " + iterations);
        System.out.println();

        printLatencyStats("DB 조회 (50ms 시뮬)", dbTimes);
        printLatencyStats("L2 (Redis) 캐시", l2Times);
        printLatencyStats("L1 (Local) 캐시", l1Times);

        double dbAvg = dbTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000;
        double l2Avg = l2Times.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000;
        double l1Avg = l1Times.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000;

        System.out.println();
        System.out.println("📊 성능 비교:");
        System.out.println("  DB 대비 L2 속도: " + String.format("%.1f배", dbAvg / l2Avg));
        System.out.println("  DB 대비 L1 속도: " + String.format("%.1f배", dbAvg / l1Avg));
        System.out.println("  L2 대비 L1 속도: " + String.format("%.1f배", l2Avg / l1Avg));

        // L1이 L2보다 빨라야 함
        assertThat(l1Avg).isLessThan(l2Avg);
    }

    @Test
    @DisplayName("캐시 HIT 비율 측정 - 다양한 접근 패턴")
    void measureCacheHitRatio_variousPatterns() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        int totalRequests = 10000;
        int uniqueKeys = 100;
        AtomicInteger l1Hits = new AtomicInteger(0);
        AtomicInteger l2Hits = new AtomicInteger(0);
        AtomicInteger dbHits = new AtomicInteger(0);

        // 초기 데이터 일부 캐싱 (warm-up)
        for (int i = 0; i < uniqueKeys / 2; i++) {
            cacheService.put(CACHE_NAME, "key:" + i, createTestReservation((long) i));
        }

        // When - Zipf 분포로 요청 (일부 키에 집중)
        Random random = new Random();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    // Zipf-like 분포: 작은 키 번호일수록 더 자주 접근
                    int keyIndex = (int) Math.floor(Math.pow(random.nextDouble(), 2) * uniqueKeys);
                    String key = "key:" + keyIndex;

                    // 캐시 조회
                    Reservation result = cacheService.getOrLoad(CACHE_NAME, key, Reservation.class, () -> {
                        dbHits.incrementAndGet();
                        return createTestReservation((long) keyIndex);
                    });

                    // L1/L2 HIT 구분은 로그로 확인 (실제로는 서비스 내부 메트릭 필요)
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long totalTime = System.currentTimeMillis() - startTime;
        int cacheHits = totalRequests - dbHits.get();
        double hitRatio = (cacheHits * 100.0) / totalRequests;

        // Then
        System.out.println("=== 캐시 HIT 비율 측정 (Zipf 분포) ===");
        System.out.println("총 요청 수: " + totalRequests);
        System.out.println("유니크 키 수: " + uniqueKeys);
        System.out.println("처리 시간: " + totalTime + "ms");
        System.out.println();
        System.out.println("캐시 HIT: " + cacheHits);
        System.out.println("DB 호출: " + dbHits.get());
        System.out.println("HIT 비율: " + String.format("%.2f%%", hitRatio));
        System.out.println("처리량: " + String.format("%.0f req/s", totalRequests * 1000.0 / totalTime));

        // Warm-up 데이터가 있으므로 최소 50% 이상 HIT
        assertThat(hitRatio).isGreaterThan(50);
    }

    @Test
    @DisplayName("동시성 성능 테스트 - 스레드 수에 따른 처리량")
    void concurrencyPerformance_throughputByThreads() throws Exception {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        int[] threadCounts = {1, 2, 4, 8, 16, 32};
        int requestsPerThread = 1000;
        String key = "perf:concurrent-test";

        // 데이터 캐싱
        cacheService.put(CACHE_NAME, key, createTestReservation(1L));

        System.out.println("=== 동시성 성능 테스트 ===");
        System.out.println("스레드당 요청 수: " + requestsPerThread);
        System.out.println();
        System.out.println(String.format("%-10s | %-15s | %-15s | %-10s",
                "스레드 수", "총 요청 수", "처리 시간(ms)", "처리량(req/s)"));
        System.out.println("-".repeat(60));

        List<Double> throughputs = new ArrayList<>();

        for (int threadCount : threadCounts) {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            int totalRequests = threadCount * requestsPerThread;

            // When
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < requestsPerThread; i++) {
                            cacheService.getOrLoad(CACHE_NAME, key, Reservation.class,
                                    () -> createTestReservation(1L));
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            long startTime = System.currentTimeMillis();
            startLatch.countDown();
            endLatch.await(30, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - startTime;

            executor.shutdown();

            // Then
            double throughput = totalRequests * 1000.0 / duration;
            throughputs.add(throughput);

            System.out.println(String.format("%-10d | %-15d | %-15d | %-10.0f",
                    threadCount, totalRequests, duration, throughput));
        }

        System.out.println();
        System.out.println("📊 분석:");
        System.out.println("  - 스레드 증가에 따른 처리량 변화 확인");
        System.out.println("  - 최적 스레드 수 이후 수확체감 발생 가능");

        // 모든 스레드 수에서 테스트가 완료되었는지 확인
        // 참고: 스레드 증가에 따른 처리량은 환경에 따라 다를 수 있음
        assertThat(throughputs).hasSize(6); // 6개의 스레드 수 테스트 완료
        assertThat(throughputs).allMatch(t -> t > 0); // 모든 처리량이 양수
    }

    @Test
    @DisplayName("캐시 워밍업 성능 측정")
    void cacheWarmup_performanceMeasurement() {
        assumeTrue(redisAvailable, "Redis is not available - skipping test");

        // Given
        int keyCount = 1000;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            keys.add("warmup:" + i);
        }

        System.out.println("=== 캐시 워밍업 성능 측정 ===");
        System.out.println("캐시할 키 수: " + keyCount);
        System.out.println();

        // When - 순차적 워밍업
        long seqStart = System.currentTimeMillis();
        for (String key : keys) {
            cacheService.put(CACHE_NAME, key, createTestReservation(1L));
        }
        long seqDuration = System.currentTimeMillis() - seqStart;

        System.out.println("순차 워밍업 시간: " + seqDuration + "ms");
        System.out.println("순차 처리량: " + String.format("%.0f keys/s", keyCount * 1000.0 / seqDuration));

        // 캐시 클리어
        cacheService.evictAll(CACHE_NAME);

        // When - 병렬 워밍업
        long parStart = System.currentTimeMillis();
        keys.parallelStream().forEach(key ->
                cacheService.put(CACHE_NAME, key, createTestReservation(1L))
        );
        long parDuration = System.currentTimeMillis() - parStart;

        System.out.println();
        System.out.println("병렬 워밍업 시간: " + parDuration + "ms");
        System.out.println("병렬 처리량: " + String.format("%.0f keys/s", keyCount * 1000.0 / parDuration));

        System.out.println();
        System.out.println("📊 속도 향상: " + String.format("%.2f배", (double) seqDuration / parDuration));
    }

    @Test
    @DisplayName("메모리 사용량 측정 (대량 캐시)")
    void memoryUsage_largeCacheSize() {
        // Given
        int[] keyCounts = {100, 1000, 5000, 10000};

        System.out.println("=== 메모리 사용량 측정 ===");
        System.out.println();

        Runtime runtime = Runtime.getRuntime();

        for (int keyCount : keyCounts) {
            // GC 후 초기 메모리
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException e) {}

            long beforeMemory = runtime.totalMemory() - runtime.freeMemory();

            // 캐시에 데이터 저장
            for (int i = 0; i < keyCount; i++) {
                String key = "memory-test:" + keyCount + ":" + i;
                cacheService.put(CACHE_NAME, key, createTestReservation((long) i));
            }

            long afterMemory = runtime.totalMemory() - runtime.freeMemory();
            long usedMemory = afterMemory - beforeMemory;

            System.out.println("키 " + keyCount + "개:");
            System.out.println("  메모리 증가: " + String.format("%.2f MB", usedMemory / 1024.0 / 1024.0));
            System.out.println("  키당 평균: " + String.format("%.2f KB", usedMemory / 1024.0 / keyCount));
            System.out.println();

            // 정리
            cacheService.evictAll(CACHE_NAME);
        }
    }

    @Test
    @DisplayName("읽기/쓰기 혼합 워크로드 성능")
    void mixedWorkload_readWritePerformance() throws Exception {
        // Given
        int totalOperations = 10000;
        double readRatio = 0.9; // 90% 읽기, 10% 쓰기
        int keyRange = 100;

        AtomicInteger readOps = new AtomicInteger(0);
        AtomicInteger writeOps = new AtomicInteger(0);
        AtomicLong totalReadTime = new AtomicLong(0);
        AtomicLong totalWriteTime = new AtomicLong(0);

        // 초기 데이터
        for (int i = 0; i < keyRange; i++) {
            cacheService.put(CACHE_NAME, "mixed:" + i, createTestReservation((long) i));
        }

        Random random = new Random();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(totalOperations);

        // When
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalOperations; i++) {
            executor.submit(() -> {
                try {
                    String key = "mixed:" + random.nextInt(keyRange);
                    boolean isRead = random.nextDouble() < readRatio;

                    long opStart = System.nanoTime();
                    if (isRead) {
                        cacheService.getOrLoad(CACHE_NAME, key, Reservation.class,
                                () -> createTestReservation(1L));
                        totalReadTime.addAndGet(System.nanoTime() - opStart);
                        readOps.incrementAndGet();
                    } else {
                        cacheService.put(CACHE_NAME, key, createTestReservation(System.currentTimeMillis()));
                        totalWriteTime.addAndGet(System.nanoTime() - opStart);
                        writeOps.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        System.out.println("=== 읽기/쓰기 혼합 워크로드 성능 ===");
        System.out.println("총 작업 수: " + totalOperations);
        System.out.println("읽기 비율: " + (readRatio * 100) + "%");
        System.out.println("처리 시간: " + totalTime + "ms");
        System.out.println();
        System.out.println("[읽기]");
        System.out.println("  작업 수: " + readOps.get());
        System.out.println("  평균 지연: " + String.format("%.3f ms",
                totalReadTime.get() / 1_000_000.0 / readOps.get()));
        System.out.println();
        System.out.println("[쓰기]");
        System.out.println("  작업 수: " + writeOps.get());
        System.out.println("  평균 지연: " + String.format("%.3f ms",
                totalWriteTime.get() / 1_000_000.0 / writeOps.get()));
        System.out.println();
        System.out.println("전체 처리량: " + String.format("%.0f ops/s", totalOperations * 1000.0 / totalTime));
    }

    private void printLatencyStats(String label, List<Long> times) {
        Collections.sort(times);
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50 = times.get(times.size() / 2);
        long p95 = times.get((int) (times.size() * 0.95));
        long p99 = times.get((int) (times.size() * 0.99));

        System.out.println(label + ":");
        System.out.println("  평균: " + String.format("%.3f ms", avg / 1_000_000));
        System.out.println("  P50:  " + String.format("%.3f ms", p50 / 1_000_000.0));
        System.out.println("  P95:  " + String.format("%.3f ms", p95 / 1_000_000.0));
        System.out.println("  P99:  " + String.format("%.3f ms", p99 / 1_000_000.0));
        System.out.println();
    }

    private void simulateDbQuery(long delayMs) {
        try {
            Thread.sleep(delayMs);
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
