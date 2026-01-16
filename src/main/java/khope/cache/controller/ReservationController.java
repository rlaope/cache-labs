package khope.cache.controller;

import khope.cache.domain.Reservation;
import khope.cache.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @GetMapping
    public ResponseEntity<List<Reservation>> findAll() {
        return ResponseEntity.ok(reservationService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Reservation> findById(@PathVariable Long id) {
        Reservation reservation = reservationService.findById(id);
        if (reservation == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reservation);
    }

    @PostMapping
    public ResponseEntity<Reservation> create(@RequestBody CreateReservationRequest request) {
        Reservation reservation = reservationService.create(
                request.customerName(),
                request.resourceName(),
                request.reservationTime()
        );
        return ResponseEntity.ok(reservation);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Reservation> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Reservation> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.cancel(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        reservationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/customer/{customerName}")
    public ResponseEntity<List<Reservation>> findByCustomerName(@PathVariable String customerName) {
        return ResponseEntity.ok(reservationService.findByCustomerName(customerName));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Void> clearCache() {
        reservationService.clearCache();
        return ResponseEntity.noContent().build();
    }

    public record CreateReservationRequest(
            String customerName,
            String resourceName,
            LocalDateTime reservationTime
    ) {}
}