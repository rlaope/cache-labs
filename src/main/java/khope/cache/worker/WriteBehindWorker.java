package khope.cache.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import khope.cache.domain.UserProfile;
import khope.cache.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Write-Behind 워커
 * Redis에 먼저 쓰고 DB에 나중에 일괄 저장하는 배치 워커
 *
 * 장점: DB 부하 감소, 쓰기 성능 향상
 * 단점: Redis 장애 시 데이터 유실 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteBehindWorker {

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    @Value("${cache.write-behind.pending-key:pending-db-updates}")
    private String pendingDbUpdatesKey;

    private static final String REDIS_USER_KEY_PREFIX = "user:";

    /**
     * 주기적으로 Redis에서 변경된 데이터를 DB에 일괄 저장
     * fixedDelay: 이전 작업 완료 후 5초 대기
     */
    @Scheduled(fixedDelayString = "${cache.write-behind.batch-delay-ms:5000}")
    @Transactional
    public void persistToDb() {
        try {
            // 1. 업데이트 대상 키들을 한꺼번에 가져옴 (Set이므로 중복 없음)
            Set<Object> pendingUserIds = redisTemplate.opsForSet().members(pendingDbUpdatesKey);

            if (pendingUserIds == null || pendingUserIds.isEmpty()) {
                return;
            }

            log.info("Write-Behind 배치 시작 - 대상: {}건", pendingUserIds.size());

            // 2. Redis에서 해당 데이터들 Multi-Get (네트워크 최적화)
            List<String> keys = pendingUserIds.stream()
                    .map(id -> REDIS_USER_KEY_PREFIX + id)
                    .toList();

            List<Object> values = redisTemplate.opsForValue().multiGet(keys);

            if (values == null) {
                log.warn("Redis에서 데이터 조회 실패");
                return;
            }

            // 3. UserProfile로 변환
            List<UserProfile> profilesToSave = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                if (value != null) {
                    try {
                        UserProfile profile = objectMapper.convertValue(value, UserProfile.class);
                        profilesToSave.add(profile);
                    } catch (Exception e) {
                        log.error("UserProfile 변환 실패 - key: {}", keys.get(i), e);
                    }
                }
            }

            if (profilesToSave.isEmpty()) {
                log.debug("저장할 프로필 없음");
                // 처리할 데이터 없어도 큐는 비움
                redisTemplate.delete(pendingDbUpdatesKey);
                return;
            }

            // 4. DB Bulk Save (JPA saveAll 사용)
            List<UserProfile> savedProfiles = userProfileRepository.saveAll(profilesToSave);
            log.info("Write-Behind 배치 완료 - {}건 DB 저장 성공", savedProfiles.size());

            // 5. 처리 완료된 키들 삭제
            // 처리 중 새로 추가된 키가 있을 수 있으므로 개별 삭제
            for (Object userId : pendingUserIds) {
                redisTemplate.opsForSet().remove(pendingDbUpdatesKey, userId);
            }

            log.debug("처리 완료된 {}건 큐에서 삭제", pendingUserIds.size());

        } catch (RedisConnectionFailureException e) {
            log.error("Redis 연결 실패로 Write-Behind 배치 실행 불가", e);
        } catch (Exception e) {
            log.error("Write-Behind 배치 처리 중 오류 발생", e);
        }
    }

    /**
     * 즉시 DB 동기화 (수동 트리거)
     * 애플리케이션 종료 전 등에 호출
     */
    public void flushImmediately() {
        log.info("Write-Behind 즉시 플러시 시작");
        persistToDb();
        log.info("Write-Behind 즉시 플러시 완료");
    }

    /**
     * 현재 대기 중인 업데이트 수 조회
     */
    public Long getPendingCount() {
        try {
            Long count = redisTemplate.opsForSet().size(pendingDbUpdatesKey);
            return count != null ? count : 0L;
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 연결 실패로 대기 건수 조회 불가");
            return -1L;
        }
    }
}
