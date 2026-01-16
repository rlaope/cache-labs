package khope.cache.service;

import khope.cache.config.CacheConfig;
import khope.cache.domain.Reservation;
import khope.cache.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final TwoLevelCacheService cacheService;

    private static final String CACHE_NAME = CacheConfig.RESERVATION_CACHE;

    private String getCacheKey(Long id) {
        return "reservation:" + id;
    }

    /**
     * 예약 조회 (이중 캐시 적용)
     */
    @Transactional(readOnly = true)
    public Reservation findById(Long id) {
        return cacheService.getOrLoad(
                CACHE_NAME,
                getCacheKey(id),
                Reservation.class,
                () -> reservationRepository.findById(id).orElse(null)
        );
    }

    /**
     * 예약 생성
     */
    @Transactional
    public Reservation create(String customerName, String resourceName, LocalDateTime reservationTime) {
        Reservation reservation = Reservation.builder()
                .customerName(customerName)
                .resourceName(resourceName)
                .reservationTime(reservationTime)
                .build();

        Reservation saved = reservationRepository.save(reservation);
        log.info("예약 생성됨 - id: {}, 고객: {}, 리소스: {}", saved.getId(), customerName, resourceName);

        // 캐시에 저장
        cacheService.put(CACHE_NAME, getCacheKey(saved.getId()), saved);

        return saved;
    }

    /**
     * 예약 확정
     */
    @Transactional
    public Reservation confirm(Long id) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다: " + id));

        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);
        log.info("예약 확정됨 - id: {}", id);

        // 캐시 갱신
        cacheService.put(CACHE_NAME, getCacheKey(id), saved);

        return saved;
    }

    /**
     * 예약 취소
     */
    @Transactional
    public Reservation cancel(Long id) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다: " + id));

        reservation.cancel();
        Reservation saved = reservationRepository.save(reservation);
        log.info("예약 취소됨 - id: {}", id);

        // 캐시에서 삭제 (취소된 예약은 캐시하지 않음)
        cacheService.evict(CACHE_NAME, getCacheKey(id));

        return saved;
    }

    /**
     * 예약 삭제
     */
    @Transactional
    public void delete(Long id) {
        reservationRepository.deleteById(id);
        cacheService.evict(CACHE_NAME, getCacheKey(id));
        log.info("예약 삭제됨 - id: {}", id);
    }

    /**
     * 전체 예약 조회 (캐시 미적용 - 리스트는 별도 캐시 전략 필요)
     */
    @Transactional(readOnly = true)
    public List<Reservation> findAll() {
        return reservationRepository.findAll();
    }

    /**
     * 고객명으로 조회
     */
    @Transactional(readOnly = true)
    public List<Reservation> findByCustomerName(String customerName) {
        return reservationRepository.findByCustomerName(customerName);
    }

    /**
     * 캐시 초기화
     */
    public void clearCache() {
        cacheService.evictAll(CACHE_NAME);
        log.info("예약 캐시 전체 삭제됨");
    }
}
