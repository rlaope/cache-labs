package khope.cache.repository;

import khope.cache.domain.UserProfile;
import khope.cache.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserId(String userId);

    List<UserProfile> findByStatus(UserStatus status);

    boolean existsByUserId(String userId);
}
