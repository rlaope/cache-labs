package khope.cache.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String resourceName;

    @Column(nullable = false)
    private LocalDateTime reservationTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Builder
    public Reservation(String customerName, String resourceName, LocalDateTime reservationTime) {
        this.customerName = customerName;
        this.resourceName = resourceName;
        this.reservationTime = reservationTime;
        this.status = ReservationStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }
}