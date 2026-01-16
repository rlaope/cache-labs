package khope.cache.repository;

import khope.cache.domain.Reservation;
import khope.cache.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByCustomerName(String customerName);

    List<Reservation> findByStatus(ReservationStatus status);

    List<Reservation> findByReservationTimeBetween(LocalDateTime start, LocalDateTime end);

    List<Reservation> findByResourceName(String resourceName);
}