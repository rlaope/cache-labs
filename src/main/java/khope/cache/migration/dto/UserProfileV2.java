package khope.cache.migration.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 유저 프로필 V2 (신버전)
 * 변경사항: name → username
 *
 * @JsonAlias로 V1 JSON(name 필드)도 V2 클래스로 역직렬화 가능
 * - V1: {"name": "khope"} → username = "khope"
 * - V2: {"username": "khope"} → username = "khope"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileV2 {

    @JsonAlias("name")  // V1의 "name" 필드도 username으로 매핑
    private String username;
    private String email;
    private Long points;
    private String bio;
    private Integer _schemaVersion;  // 스키마 버전 (자동 추가됨)

    /**
     * 테스트용 샘플 데이터 생성
     */
    public static UserProfileV2 sample(String username) {
        return UserProfileV2.builder()
                .username(username)
                .email(username.toLowerCase() + "@example.com")
                .points(1000L)
                .bio("Hello, I'm " + username)
                ._schemaVersion(2)
                .build();
    }

    /**
     * V1에서 마이그레이션된 데이터 검증용
     */
    public static UserProfileV2 fromV1(UserProfileV1 v1) {
        return UserProfileV2.builder()
                .username(v1.getName())  // name → username
                .email(v1.getEmail())
                .points(v1.getPoints())
                .bio(v1.getBio())
                ._schemaVersion(2)
                .build();
    }
}
