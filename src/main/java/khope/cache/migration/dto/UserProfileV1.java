package khope.cache.migration.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 유저 프로필 V1 (구버전)
 * 필드: name (향후 username으로 변경됨)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileV1 {

    private String name;        // v2에서 username으로 변경
    private String email;
    private Long points;
    private String bio;

    /**
     * 테스트용 샘플 데이터 생성
     */
    public static UserProfileV1 sample(String name) {
        return UserProfileV1.builder()
                .name(name)
                .email(name.toLowerCase() + "@example.com")
                .points(1000L)
                .bio("Hello, I'm " + name)
                .build();
    }
}
