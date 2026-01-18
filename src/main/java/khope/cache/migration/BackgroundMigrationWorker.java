package khope.cache.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 백그라운드 캐시 마이그레이션 워커
 *
 * Redis CPU 여유분을 활용해 선제적으로 구버전 데이터를 마이그레이션한다.
 * Lazy Migration과 함께 사용하여 마이그레이션 기간을 단축한다.
 *
 * 활성화: cache.migration.background.enabled=true
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cache.migration.background.enabled", havingValue = "true")
public class BackgroundMigrationWorker {

    private final StringRedisTemplate stringRedisTemplate;
    private final SchemaMigrationService migrationService;

    @Value("${cache.migration.background.key-pattern:user:*}")
    private String keyPattern;

    @Value("${cache.migration.background.batch-size:100}")
    private int defaultBatchSize;

    @Value("${cache.migration.background.cpu-limit:60}")
    private double cpuLimit;

    // 마이그레이션 상태
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicLong totalMigrated = new AtomicLong(0);
    private final AtomicLong totalScanned = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    // SCAN 커서 위치 저장 (이어서 스캔하기 위해)
    private volatile long lastCursor = 0;
    private volatile boolean scanCompleted = false;

    /**
     * 백그라운드 마이그레이션 실행
     * 10초마다 실행, Redis CPU 상태에 따라 배치 크기 조절
     */
    @Scheduled(fixedDelayString = "${cache.migration.background.interval-ms:10000}")
    public void migrateInBackground() {
        if (scanCompleted) {
            log.debug("마이그레이션 완료 상태, 스킵");
            return;
        }

        if (!isRunning.compareAndSet(false, true)) {
            log.debug("이전 마이그레이션 작업 진행 중, 스킵");
            return;
        }

        try {
            // Redis CPU 확인
            double cpuUsage = getRedisCpuUsage();
            if (cpuUsage > cpuLimit) {
                log.warn("Redis CPU 사용량 높음 ({}%), 마이그레이션 일시 중단", cpuUsage);
                return;
            }

            // CPU 여유에 따라 배치 크기 동적 조절
            int batchSize = calculateBatchSize(cpuUsage);
            if (batchSize == 0) {
                return;
            }

            log.debug("백그라운드 마이그레이션 시작 - CPU: {}%, 배치: {}", cpuUsage, batchSize);

            // SCAN으로 키 조회 및 마이그레이션
            int migrated = scanAndMigrate(batchSize);

            if (migrated > 0) {
                log.info("백그라운드 마이그레이션 진행 - 이번 배치: {}건, 누적: {}건, 스캔: {}건",
                        migrated, totalMigrated.get(), totalScanned.get());
            }

        } catch (Exception e) {
            log.error("백그라운드 마이그레이션 중 오류 발생", e);
            totalErrors.incrementAndGet();
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * SCAN으로 키를 순회하며 Lua 스크립트로 마이그레이션
     */
    private int scanAndMigrate(int batchSize) {
        int migrated = 0;
        int scanned = 0;

        ScanOptions options = ScanOptions.scanOptions()
                .match(keyPattern)
                .count(batchSize)
                .build();

        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            List<String> keysToMigrate = new ArrayList<>();

            while (cursor.hasNext() && scanned < batchSize) {
                String key = cursor.next();
                keysToMigrate.add(key);
                scanned++;
            }

            // 배치로 마이그레이션 실행
            for (String key : keysToMigrate) {
                Long result = migrationService.migrateWithLuaScript(key);
                if (result != null && result == 1) {
                    migrated++;
                    totalMigrated.incrementAndGet();
                }
                totalScanned.incrementAndGet();
            }

            // 더 이상 키가 없으면 완료
            if (!cursor.hasNext() && keysToMigrate.isEmpty()) {
                scanCompleted = true;
                log.info("백그라운드 마이그레이션 완료! 총 마이그레이션: {}건, 총 스캔: {}건",
                        totalMigrated.get(), totalScanned.get());
            }

        } catch (Exception e) {
            log.error("SCAN 중 오류 발생", e);
        }

        return migrated;
    }

    /**
     * Redis CPU 사용량 조회
     * INFO 명령어로 used_cpu_sys + used_cpu_user 계산
     */
    private double getRedisCpuUsage() {
        try {
            RedisConnection connection = stringRedisTemplate
                    .getConnectionFactory()
                    .getConnection();

            Properties info = connection.serverCommands().info("cpu");
            if (info == null) {
                return 0;
            }

            // used_cpu_sys, used_cpu_user 합산
            double sysCpu = Double.parseDouble(
                    info.getProperty("used_cpu_sys", "0"));
            double userCpu = Double.parseDouble(
                    info.getProperty("used_cpu_user", "0"));

            // 실제 CPU 퍼센트 계산은 복잡하므로 간단히 추정
            // 프로덕션에서는 모니터링 시스템 (Prometheus 등)에서 가져오는 것이 정확함
            return Math.min((sysCpu + userCpu) % 100, 100);

        } catch (Exception e) {
            log.debug("Redis CPU 정보 조회 실패, 기본값 사용");
            return 20; // 기본값
        }
    }

    /**
     * CPU 사용량에 따른 배치 크기 동적 계산
     */
    private int calculateBatchSize(double cpuUsage) {
        if (cpuUsage > 70) return 0;        // 중단
        if (cpuUsage > 60) return 50;       // 최소
        if (cpuUsage > 40) return 100;      // 보통
        if (cpuUsage > 20) return 300;      // 여유
        return defaultBatchSize;             // 매우 여유
    }

    /**
     * 마이그레이션 진행 상황
     */
    public MigrationProgress getProgress() {
        return new MigrationProgress(
                totalMigrated.get(),
                totalScanned.get(),
                totalErrors.get(),
                scanCompleted,
                isRunning.get()
        );
    }

    /**
     * 마이그레이션 상태 초기화 (재시작용)
     */
    public void reset() {
        lastCursor = 0;
        scanCompleted = false;
        totalMigrated.set(0);
        totalScanned.set(0);
        totalErrors.set(0);
        log.info("백그라운드 마이그레이션 상태 초기화");
    }

    /**
     * 마이그레이션 완료 여부
     */
    public boolean isCompleted() {
        return scanCompleted;
    }

    public record MigrationProgress(
            long migrated,
            long scanned,
            long errors,
            boolean completed,
            boolean running
    ) {
        public double progressRate() {
            return scanned > 0 ? (double) migrated / scanned * 100 : 0;
        }
    }
}
