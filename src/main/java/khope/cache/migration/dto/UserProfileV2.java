package khope.cache.migration.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 유저 프로필 V2 (신버전)
 *
 * 마이그레이션 전략:
 * 1. 필드명 변경: @JsonAlias("oldName") - V1의 name → V2의 username
 * 2. 새 필드 추가: @Builder.Default + @JsonSetter(nulls = Nulls.SKIP)
 *    - JSON에 필드가 없거나 null이면 기본값 사용
 *
 * 예시:
 * - V1: {"name": "khope", "points": 100}
 * - V2: {"username": "khope", "points": 100, "premiumLevel": 0, "settings": {...}}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileV2 {

    @JsonAlias("name")  // V1의 "name" 필드도 username으로 매핑
    private String username;

    private String email;

    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)  // null이면 기본값 유지
    private Long points = 0L;

    private String bio;

    // ========== V2에서 새로 추가된 필드들 ==========

    /**
     * 프리미엄 등급 (V2에서 추가)
     * V1 JSON에는 없음 → 기본값 0
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Integer premiumLevel = 0;

    /**
     * 알림 설정 (V2에서 추가)
     * V1 JSON에는 없음 → 기본값 true
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private Boolean notificationEnabled = true;

    /**
     * 마지막 로그인 타임스탬프 (V2에서 추가)
     * V1 JSON에는 없음 → 기본값 null 허용
     */
    private Long lastLoginAt;

    private Integer _schemaVersion;

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
