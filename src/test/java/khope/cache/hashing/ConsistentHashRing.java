package khope.cache.hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent Hashing 구현
 *
 * README 4.5 Consistent Hashing 구현
 * - 노드 추가/삭제 시 최소한의 키 재배치
 * - 가상 노드를 통한 균등 분포
 */
public class ConsistentHashRing<T> {

    private final ConcurrentSkipListMap<Long, T> ring = new ConcurrentSkipListMap<>();
    private final int numberOfVirtualNodes;
    private final Set<T> physicalNodes = new HashSet<>();

    public ConsistentHashRing(int numberOfVirtualNodes) {
        this.numberOfVirtualNodes = numberOfVirtualNodes;
    }

    /**
     * 물리 노드 추가 - 가상 노드들을 링에 배치
     */
    public void addNode(T node) {
        physicalNodes.add(node);
        for (int i = 0; i < numberOfVirtualNodes; i++) {
            long hash = hash(node.toString() + "#" + i);
            ring.put(hash, node);
        }
    }

    /**
     * 물리 노드 제거 - 해당 가상 노드들을 링에서 제거
     */
    public void removeNode(T node) {
        physicalNodes.remove(node);
        for (int i = 0; i < numberOfVirtualNodes; i++) {
            long hash = hash(node.toString() + "#" + i);
            ring.remove(hash);
        }
    }

    /**
     * 키에 해당하는 노드 찾기 - 시계방향으로 가장 가까운 노드
     */
    public T getNode(String key) {
        if (ring.isEmpty()) {
            return null;
        }

        long hash = hash(key);
        Map.Entry<Long, T> entry = ring.ceilingEntry(hash);

        if (entry == null) {
            entry = ring.firstEntry();
        }

        return entry.getValue();
    }

    /**
     * 모든 키에 대한 노드 매핑 계산
     */
    public Map<String, T> getNodeMappings(List<String> keys) {
        Map<String, T> mappings = new HashMap<>();
        for (String key : keys) {
            mappings.put(key, getNode(key));
        }
        return mappings;
    }

    /**
     * 노드 변경 시 재배치될 키 찾기
     */
    public Set<String> getKeysToRelocate(List<String> keys, T nodeToRemove) {
        Set<String> keysToRelocate = new HashSet<>();

        // 현재 매핑 저장
        Map<String, T> currentMappings = getNodeMappings(keys);

        // 노드 제거
        removeNode(nodeToRemove);

        // 새로운 매핑과 비교
        for (String key : keys) {
            T newNode = getNode(key);
            T oldNode = currentMappings.get(key);
            if (!Objects.equals(newNode, oldNode)) {
                keysToRelocate.add(key);
            }
        }

        // 노드 복구
        addNode(nodeToRemove);

        return keysToRelocate;
    }

    /**
     * 노드별 키 분포 계산
     */
    public Map<T, Integer> getKeyDistribution(List<String> keys) {
        Map<T, Integer> distribution = new HashMap<>();
        for (T node : physicalNodes) {
            distribution.put(node, 0);
        }

        for (String key : keys) {
            T node = getNode(key);
            if (node != null) {
                distribution.merge(node, 1, Integer::sum);
            }
        }

        return distribution;
    }

    /**
     * MD5 해시 함수
     */
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ((long) (digest[0] & 0xFF) << 56)
                    | ((long) (digest[1] & 0xFF) << 48)
                    | ((long) (digest[2] & 0xFF) << 40)
                    | ((long) (digest[3] & 0xFF) << 32)
                    | ((long) (digest[4] & 0xFF) << 24)
                    | ((long) (digest[5] & 0xFF) << 16)
                    | ((long) (digest[6] & 0xFF) << 8)
                    | ((long) (digest[7] & 0xFF));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public int getRingSize() {
        return ring.size();
    }

    public Set<T> getPhysicalNodes() {
        return Collections.unmodifiableSet(physicalNodes);
    }

    public int getVirtualNodeCount() {
        return numberOfVirtualNodes;
    }
}