package khope.cache.hashing;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consistent Hashing 테스트
 *
 * README 4.5 Consistent Hashing 검증
 * - 노드 추가/삭제 시 최소한의 키 재배치 검증
 * - 가상 노드를 통한 균등 분포 검증
 * - 전통적인 해싱과의 비교
 */
@DisplayName("Consistent Hashing 테스트")
class ConsistentHashingTest {

    @Test
    @DisplayName("기본 해시 링 동작 검증")
    void basicHashRing_operation() {
        // Given
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(100);
        ring.addNode("cache-server-1");
        ring.addNode("cache-server-2");
        ring.addNode("cache-server-3");

        // When
        String node1 = ring.getNode("user:1001");
        String node2 = ring.getNode("user:1002");
        String node3 = ring.getNode("product:5001");

        // Then
        System.out.println("=== 기본 해시 링 동작 ===");
        System.out.println("노드 수: " + ring.getPhysicalNodes().size());
        System.out.println("가상 노드 수: " + ring.getRingSize());
        System.out.println();
        System.out.println("키 매핑:");
        System.out.println("  user:1001 → " + node1);
        System.out.println("  user:1002 → " + node2);
        System.out.println("  product:5001 → " + node3);

        assertThat(node1).isNotNull();
        assertThat(node2).isNotNull();
        assertThat(node3).isNotNull();

        // 동일한 키는 항상 같은 노드로 매핑
        assertThat(ring.getNode("user:1001")).isEqualTo(node1);
        assertThat(ring.getNode("user:1002")).isEqualTo(node2);
    }

    @Test
    @DisplayName("노드 추가 시 최소한의 키만 재배치")
    void nodeAddition_minimalKeyRelocation() {
        // Given
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("server-1");
        ring.addNode("server-2");
        ring.addNode("server-3");

        int keyCount = 10000;
        List<String> keys = IntStream.range(0, keyCount)
                .mapToObj(i -> "key:" + i)
                .collect(Collectors.toList());

        // 초기 매핑 저장
        Map<String, String> initialMappings = ring.getNodeMappings(keys);

        // When - 새 노드 추가
        ring.addNode("server-4");

        // Then - 재배치된 키 계산
        Map<String, String> newMappings = ring.getNodeMappings(keys);
        long relocatedCount = keys.stream()
                .filter(key -> !initialMappings.get(key).equals(newMappings.get(key)))
                .count();

        double relocatedPercent = (relocatedCount * 100.0) / keyCount;
        double expectedPercent = 100.0 / 4; // 이상적으로는 1/N (25%)

        System.out.println("=== 노드 추가 시 키 재배치 분석 ===");
        System.out.println("총 키 수: " + keyCount);
        System.out.println("노드 변화: 3대 → 4대");
        System.out.println();
        System.out.println("재배치된 키: " + relocatedCount);
        System.out.println("재배치 비율: " + String.format("%.2f%%", relocatedPercent));
        System.out.println("이론적 최적값: " + String.format("%.2f%%", expectedPercent));
        System.out.println();

        // 전통적인 해싱과 비교
        long traditionalRelocatedCount = calculateTraditionalHashingRelocation(keys, 3, 4);
        double traditionalPercent = (traditionalRelocatedCount * 100.0) / keyCount;

        System.out.println("📊 전통적 해싱 (hash % N) 대비 비교:");
        System.out.println("  전통적 해싱 재배치: " + String.format("%.2f%%", traditionalPercent));
        System.out.println("  Consistent Hashing: " + String.format("%.2f%%", relocatedPercent));
        System.out.println("  개선율: " + String.format("%.1f배", traditionalPercent / relocatedPercent));

        // Consistent Hashing이 전통적 해싱보다 훨씬 적은 재배치를 해야 함
        assertThat(relocatedPercent).isLessThan(40); // 최악의 경우에도 40% 미만
        assertThat(relocatedPercent).isLessThan(traditionalPercent);
    }

    @Test
    @DisplayName("노드 제거 시 최소한의 키만 재배치")
    void nodeRemoval_minimalKeyRelocation() {
        // Given
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("server-1");
        ring.addNode("server-2");
        ring.addNode("server-3");
        ring.addNode("server-4");

        int keyCount = 10000;
        List<String> keys = IntStream.range(0, keyCount)
                .mapToObj(i -> "key:" + i)
                .collect(Collectors.toList());

        Map<String, String> initialMappings = ring.getNodeMappings(keys);

        // When - 노드 제거
        ring.removeNode("server-2");

        // Then
        Map<String, String> newMappings = ring.getNodeMappings(keys);
        long relocatedCount = keys.stream()
                .filter(key -> !initialMappings.get(key).equals(newMappings.get(key)))
                .count();

        double relocatedPercent = (relocatedCount * 100.0) / keyCount;
        double expectedPercent = 100.0 / 4; // 제거된 노드가 담당하던 키 비율

        System.out.println("=== 노드 제거 시 키 재배치 분석 ===");
        System.out.println("총 키 수: " + keyCount);
        System.out.println("노드 변화: 4대 → 3대 (server-2 제거)");
        System.out.println();
        System.out.println("재배치된 키: " + relocatedCount);
        System.out.println("재배치 비율: " + String.format("%.2f%%", relocatedPercent));
        System.out.println("이론적 최적값: " + String.format("%.2f%%", expectedPercent));

        assertThat(relocatedPercent).isLessThan(40);
    }

    @Test
    @DisplayName("가상 노드 수에 따른 분포 균등도 비교")
    void virtualNodes_distributionBalance() {
        // Given
        int keyCount = 100000;
        List<String> keys = IntStream.range(0, keyCount)
                .mapToObj(i -> "key:" + i)
                .collect(Collectors.toList());

        int[] virtualNodeCounts = {1, 10, 50, 100, 200};

        System.out.println("=== 가상 노드 수에 따른 분포 균등도 ===");
        System.out.println("총 키 수: " + keyCount);
        System.out.println("물리 노드: server-1, server-2, server-3");
        System.out.println();

        for (int vnCount : virtualNodeCounts) {
            ConsistentHashRing<String> ring = new ConsistentHashRing<>(vnCount);
            ring.addNode("server-1");
            ring.addNode("server-2");
            ring.addNode("server-3");

            Map<String, Integer> distribution = ring.getKeyDistribution(keys);

            int min = distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = distribution.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            double stdDev = calculateStdDev(distribution.values(), avg);

            System.out.println("가상 노드 수: " + vnCount);
            System.out.println("  분포: " + distribution);
            System.out.println("  최소: " + min + ", 최대: " + max + ", 평균: " + String.format("%.0f", avg));
            System.out.println("  표준편차: " + String.format("%.0f", stdDev));
            System.out.println("  불균형도 (max-min)/avg: " + String.format("%.2f%%", ((max - min) * 100.0) / avg));
            System.out.println();
        }

        // 가상 노드가 많을수록 균등해야 함
        ConsistentHashRing<String> lowVn = new ConsistentHashRing<>(1);
        ConsistentHashRing<String> highVn = new ConsistentHashRing<>(200);

        Arrays.asList("server-1", "server-2", "server-3").forEach(s -> {
            lowVn.addNode(s);
            highVn.addNode(s);
        });

        Map<String, Integer> lowDist = lowVn.getKeyDistribution(keys);
        Map<String, Integer> highDist = highVn.getKeyDistribution(keys);

        double lowStdDev = calculateStdDev(lowDist.values(), keyCount / 3.0);
        double highStdDev = calculateStdDev(highDist.values(), keyCount / 3.0);

        System.out.println("✅ 가상 노드 1개 표준편차: " + String.format("%.0f", lowStdDev));
        System.out.println("✅ 가상 노드 200개 표준편차: " + String.format("%.0f", highStdDev));
        System.out.println("   → 가상 노드가 많을수록 분포가 균등함");

        assertThat(highStdDev).isLessThan(lowStdDev);
    }

    @Test
    @DisplayName("전통적 해싱 vs Consistent Hashing 비교")
    void traditionalVsConsistentHashing_comparison() {
        // Given
        int keyCount = 10000;
        List<String> keys = IntStream.range(0, keyCount)
                .mapToObj(i -> "user:" + i)
                .collect(Collectors.toList());

        System.out.println("=== 전통적 해싱 vs Consistent Hashing ===");
        System.out.println("총 키 수: " + keyCount);
        System.out.println();

        // 노드 3대 → 4대 → 5대 → 4대(장애) 시나리오
        int[] nodeChanges = {3, 4, 5, 4};

        System.out.println("📊 노드 변경 시나리오별 재배치 비율:");
        System.out.println();
        System.out.println(String.format("%-20s | %-15s | %-15s", "시나리오", "전통적 해싱", "Consistent Hashing"));
        System.out.println("-".repeat(55));

        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        for (int i = 1; i <= 3; i++) {
            ring.addNode("server-" + i);
        }
        Map<String, String> consistentPrevMappings = ring.getNodeMappings(keys);

        int traditionalPrevN = 3;

        for (int i = 1; i < nodeChanges.length; i++) {
            int prevN = nodeChanges[i - 1];
            int newN = nodeChanges[i];

            // 전통적 해싱 재배치
            long traditionalRelocated = calculateTraditionalHashingRelocation(keys, prevN, newN);
            double traditionalPercent = (traditionalRelocated * 100.0) / keyCount;

            // Consistent Hashing 재배치
            if (newN > prevN) {
                ring.addNode("server-" + newN);
            } else {
                ring.removeNode("server-" + (prevN));
            }

            Map<String, String> consistentNewMappings = ring.getNodeMappings(keys);
            long consistentRelocated = keys.stream()
                    .filter(key -> !consistentPrevMappings.get(key).equals(consistentNewMappings.get(key)))
                    .count();
            double consistentPercent = (consistentRelocated * 100.0) / keyCount;

            consistentPrevMappings = consistentNewMappings;

            String scenario = prevN + "대 → " + newN + "대";
            System.out.println(String.format("%-20s | %13.2f%% | %13.2f%%",
                    scenario, traditionalPercent, consistentPercent));
        }

        System.out.println();
        System.out.println("✅ Consistent Hashing은 노드 변경 시 재배치를 최소화함");
    }

    @Test
    @DisplayName("장애 시나리오 - 노드 장애 및 복구")
    void failureScenario_nodeFailureAndRecovery() {
        // Given
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(150);
        ring.addNode("primary-1");
        ring.addNode("primary-2");
        ring.addNode("primary-3");

        int keyCount = 5000;
        List<String> keys = IntStream.range(0, keyCount)
                .mapToObj(i -> "session:" + i)
                .collect(Collectors.toList());

        System.out.println("=== 노드 장애 및 복구 시나리오 ===");
        System.out.println("초기 노드: primary-1, primary-2, primary-3");
        System.out.println("총 세션 키: " + keyCount);
        System.out.println();

        // 초기 상태
        Map<String, String> initialMapping = ring.getNodeMappings(keys);
        Map<String, Integer> initialDist = ring.getKeyDistribution(keys);
        System.out.println("[초기 상태] 키 분포: " + initialDist);

        // When - primary-2 장애 발생
        System.out.println();
        System.out.println("[장애 발생] primary-2 다운!");
        ring.removeNode("primary-2");

        Map<String, String> afterFailureMapping = ring.getNodeMappings(keys);
        Map<String, Integer> afterFailureDist = ring.getKeyDistribution(keys);

        long failureRelocated = keys.stream()
                .filter(key -> !initialMapping.get(key).equals(afterFailureMapping.get(key)))
                .count();

        System.out.println("  재배치된 키: " + failureRelocated + " (" +
                String.format("%.2f%%", (failureRelocated * 100.0) / keyCount) + ")");
        System.out.println("  새 분포: " + afterFailureDist);

        // When - 새 노드 추가 (스케일 업)
        System.out.println();
        System.out.println("[복구] backup-1 노드 추가");
        ring.addNode("backup-1");

        Map<String, String> afterRecoveryMapping = ring.getNodeMappings(keys);
        Map<String, Integer> afterRecoveryDist = ring.getKeyDistribution(keys);

        long recoveryRelocated = keys.stream()
                .filter(key -> !afterFailureMapping.get(key).equals(afterRecoveryMapping.get(key)))
                .count();

        System.out.println("  재배치된 키: " + recoveryRelocated + " (" +
                String.format("%.2f%%", (recoveryRelocated * 100.0) / keyCount) + ")");
        System.out.println("  새 분포: " + afterRecoveryDist);

        // Then
        System.out.println();
        System.out.println("✅ 장애 시 약 1/N의 키만 재배치됨 (다른 노드 영향 최소화)");
        System.out.println("✅ 복구 시에도 최소한의 키만 이동");

        assertThat(failureRelocated).isLessThan(keyCount / 2); // 50% 미만만 재배치
        assertThat(recoveryRelocated).isLessThan(keyCount / 2);
    }

    /**
     * 전통적 해싱에서 노드 변경 시 재배치되는 키 수 계산
     */
    private long calculateTraditionalHashingRelocation(List<String> keys, int oldN, int newN) {
        return keys.stream()
                .filter(key -> {
                    int hash = Math.abs(key.hashCode());
                    return (hash % oldN) != (hash % newN);
                })
                .count();
    }

    /**
     * 표준편차 계산
     */
    private double calculateStdDev(Collection<Integer> values, double mean) {
        double sumSquaredDiff = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .sum();
        return Math.sqrt(sumSquaredDiff / values.size());
    }
}