# 캐시 스키마 마이그레이션 가이드

## 문제 상황

캐시된 JSON 값의 필드명이 변경되어 하위호환성이 깨지는 경우가 발생할 수 있다.

```json
// Before (기존)
{ "name": "khope" }

// After (신규)
{ "username": "khope" }
```

### 현재 시스템 상태 예시
| 지표 | 값 | 위험 수준 |
|------|-----|----------|
| Redis 메모리 | 80% | 주의 |
| Cache Hit Rate | 70% | 정상 |
| Redis CPU | 20% | 여유 |
| DB 장애 임계치 | Hit Rate < 50% | 위험 |

**핵심 제약사항**: Cache hit rate가 50% 이하로 떨어지면 DB 장애 발생
→ **일괄 캐시 무효화(flush)는 절대 금지**

---

## 마이그레이션 전략 비교

| 전략 | 메모리 영향 | Hit Rate 영향 | 복잡도 | 권장 상황 |
|------|------------|---------------|--------|----------|
| Lazy Migration | 없음 | 없음 | 중 | **대부분의 경우 권장** |
| Versioned Key | 2배 증가 | 일시적 하락 | 하 | 메모리 여유 있을 때 |
| Dual Write | 2배 증가 | 없음 | 상 | 완벽한 정합성 필요시 |
| Gradual TTL | 없음 | 점진적 하락 | 하 | 필드 삭제/추가만 있을 때 |
| Shadow Mode | 없음 | 없음 | 상 | 대규모 변경, 검증 필요시 |

---

## 전략 1: Lazy Migration (읽기 시점 변환)

가장 안전하고 실용적인 방법. 캐시를 읽을 때 구버전이면 신버전으로 변환하여 반환하고, 다시 저장한다.

### Redis CPU 활용한 마이그레이션 가속화

현재 Redis CPU 20%라면 **40~50%p 여유**가 있다. 이를 활용해 백그라운드에서 선제적으로 마이그레이션할 수 있다.

#### 방법 1: Lua 스크립트로 서버 사이드 변환

애플리케이션 ↔ Redis 네트워크 왕복 없이 Redis 내부에서 직접 변환한다.

```lua
-- migrate_user.lua
-- KEYS[1] = 변환할 키
-- 반환: 1(변환됨), 0(이미 신버전), -1(키 없음)

local key = KEYS[1]
local data = redis.call('GET', key)

if not data then
    return -1
end

-- JSON 파싱 (cjson 사용)
local obj = cjson.decode(data)

-- 이미 신버전이면 스킵
if obj['username'] then
    return 0
end

-- 구버전 → 신버전 변환
if obj['name'] then
    obj['username'] = obj['name']
    obj['name'] = nil

    -- TTL 유지하면서 저장
    local ttl = redis.call('TTL', key)
    local newData = cjson.encode(obj)

    if ttl > 0 then
        redis.call('SETEX', key, ttl, newData)
    else
        redis.call('SET', key, newData)
    end

    return 1
end

return 0
```

**장점**: 네트워크 왕복 1회로 읽기+변환+쓰기 완료
**주의**: Lua 실행 중 Redis는 블로킹됨 (single thread)

#### 방법 2: SCAN + 배치로 백그라운드 마이그레이션

별도 워커가 백그라운드에서 점진적으로 스캔하며 변환한다.

```java
@Component
@RequiredArgsConstructor
public class BackgroundMigrationWorker {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // 미리 로드한 Lua 스크립트
    private RedisScript<Long> migrateScript;

    /**
     * 백그라운드 마이그레이션 (10초마다 실행)
     * Redis CPU 사용량 모니터링하면서 배치 크기 조절
     */
    @Scheduled(fixedDelay = 10000)
    public void migrateInBackground() {
        int batchSize = calculateBatchSize(); // CPU 여유에 따라 조절
        int migrated = 0;

        // SCAN으로 키 순회
        ScanOptions options = ScanOptions.scanOptions()
            .match("user:*")
            .count(batchSize)
            .build();

        try (Cursor<String> cursor = stringRedisTemplate
                .scan(options)) {

            while (cursor.hasNext() && migrated < batchSize) {
                String key = cursor.next();

                // Lua 스크립트로 변환 (서버 사이드)
                Long result = redisTemplate.execute(
                    migrateScript,
                    Collections.singletonList(key)
                );

                if (result == 1) {
                    migrated++;
                }
            }
        }

        if (migrated > 0) {
            log.info("백그라운드 마이그레이션: {}건 완료", migrated);
        }
    }

    /**
     * Redis CPU 사용량에 따라 배치 크기 동적 조절
     */
    private int calculateBatchSize() {
        // Redis INFO 명령으로 CPU 확인 (또는 모니터링 시스템에서)
        // CPU 20% → 배치 500
        // CPU 40% → 배치 200
        // CPU 60% → 배치 50
        // CPU 70%+ → 중단

        double cpuUsage = getRedisCpuUsage();

        if (cpuUsage > 70) return 0;      // 중단
        if (cpuUsage > 60) return 50;
        if (cpuUsage > 40) return 200;
        return 500;                        // 여유로움
    }
}
```

#### 방법 3: 파이프라인으로 네트워크 최적화

여러 키를 한 번의 네트워크 왕복으로 처리한다.

```java
public void migrateBatchWithPipeline(List<String> keys) {
    List<Object> results = redisTemplate.executePipelined(
        (RedisCallback<Object>) connection -> {
            for (String key : keys) {
                // 파이프라인에 Lua 스크립트 실행 추가
                connection.eval(
                    migrateScriptBytes,
                    ReturnType.INTEGER,
                    1,
                    key.getBytes()
                );
            }
            return null;
        }
    );

    long migrated = results.stream()
        .filter(r -> Long.valueOf(1).equals(r))
        .count();

    log.info("파이프라인 마이그레이션: {}/{} 건", migrated, keys.size());
}
```

#### CPU 사용량 기반 속도 조절

```
Redis CPU 20% (현재)
├── 목표: 60% 이하 유지
├── 여유분: 40%p
└── 활용 전략:
    ├── 피크 시간대 (09:00~18:00): 배치 100, 간격 30초
    ├── 비피크 시간대 (18:00~09:00): 배치 500, 간격 5초
    └── CPU 60% 초과 시: 자동 중단
```

#### 마이그레이션 속도 비교

| 방식 | 처리량 (키/초) | 네트워크 | Redis CPU |
|------|---------------|----------|-----------|
| Lazy (요청 시) | 트래픽 의존 | 키당 2회 | 낮음 |
| Lua 배치 | 1,000~5,000 | 키당 1회 | 중간 |
| 파이프라인 | 5,000~10,000 | 배치당 1회 | 중간 |
| Lua + 파이프라인 | 10,000+ | 배치당 1회 | 높음 |

#### 권장 조합

```
1. Lazy Migration (기본) - 읽히는 데이터 즉시 변환
   +
2. 백그라운드 Lua 배치 - CPU 여유분으로 선제적 변환
   ↓
   결과: 마이그레이션 기간 단축 + 안정성 확보
```

#### 주의사항

```
⚠️ Lua 스크립트는 원자적 실행 (Redis 블로킹)
   → 단일 스크립트 실행 시간 < 100ms 유지
   → 너무 많은 키를 한 스크립트에서 처리하지 않기

⚠️ KEYS 명령 사용 금지
   → O(N) 블로킹, 프로덕션에서 장애 유발
   → 반드시 SCAN 사용 (논블로킹, 커서 기반)

⚠️ CPU 모니터링 필수
   → 60% 넘으면 배치 크기 줄이기
   → 70% 넘으면 일시 중단
```

### 동작 원리

```
1. 캐시 조회
2. 구버전 감지 시 → 신버전으로 변환 → 반환 + 캐시 갱신
3. 신버전이면 → 그대로 반환
```

### 장점
- Cache hit rate 영향 없음 (기존 데이터 그대로 사용)
- 메모리 추가 사용 없음 (기존 키 덮어쓰기)
- 자연스러운 점진적 마이그레이션

### 단점
- 변환 로직이 애플리케이션에 남아있어야 함
- 모든 키가 마이그레이션 완료되기까지 시간 소요

### 구현 패턴 (의사코드)

```java
public UserProfile getFromCache(String key) {
    Object cached = redis.get(key);

    if (cached == null) {
        return null;
    }

    // 버전 감지 및 변환
    if (isOldVersion(cached)) {
        UserProfile migrated = migrateToNewVersion(cached);
        redis.set(key, migrated, remainingTTL(key)); // 기존 TTL 유지
        return migrated;
    }

    return (UserProfile) cached;
}

private boolean isOldVersion(Object data) {
    // "name" 필드 존재 && "username" 필드 없음 → 구버전
    Map<String, Object> map = (Map<String, Object>) data;
    return map.containsKey("name") && !map.containsKey("username");
}

private UserProfile migrateToNewVersion(Object oldData) {
    Map<String, Object> old = (Map<String, Object>) oldData;
    return UserProfile.builder()
        .username((String) old.get("name"))  // name → username
        .build();
}
```

### 배포 순서

```
Phase 1: 변환 로직 포함한 코드 배포 (모든 서버)
         ↓
Phase 2: 자연스럽게 읽히는 데이터부터 마이그레이션 진행
         ↓
Phase 3: 일정 기간 후 (2~3 TTL 주기) 모니터링
         ↓
Phase 4: 구버전 데이터 없음 확인 후 변환 로직 제거
```

---

## 전략 2: Versioned Key (키에 버전 포함)

키 자체에 버전을 포함시켜 신/구 버전을 분리한다.

### 동작 원리

```
기존: user:123
신규: user:v2:123

1. 신버전 키 먼저 조회
2. 없으면 구버전 키 조회 → 변환 → 신버전 키로 저장
3. 구버전 키는 TTL 만료로 자연 삭제
```

### 장점
- 롤백이 쉬움 (구버전 키가 그대로 존재)
- 명확한 버전 구분

### 단점
- **메모리 일시적 2배 사용** (현재 80%면 위험)
- Hit rate 일시적 하락 (신버전 키가 없으므로)

### 사용 가능 조건
```
Redis 메모리 < 50% 일 때만 권장
```

### 구현 패턴 (의사코드)

```java
private static final int CURRENT_VERSION = 2;

public UserProfile getFromCache(String userId) {
    String newKey = "user:v" + CURRENT_VERSION + ":" + userId;
    String oldKey = "user:" + userId;

    // 1. 신버전 먼저 조회
    Object newData = redis.get(newKey);
    if (newData != null) {
        return (UserProfile) newData;
    }

    // 2. 구버전 조회 및 마이그레이션
    Object oldData = redis.get(oldKey);
    if (oldData != null) {
        UserProfile migrated = migrateToNewVersion(oldData);
        redis.set(newKey, migrated, DEFAULT_TTL);
        // 구버전은 삭제하지 않음 (TTL 만료까지 유지 → 롤백 대비)
        return migrated;
    }

    return null; // Cache miss
}
```

---

## 전략 3: Dual Write (이중 쓰기)

쓰기 시점에 구버전/신버전 모두 저장한다.

### 동작 원리

```
쓰기 시:
  - 구버전 키에 구버전 형식으로 저장
  - 신버전 키에 신버전 형식으로 저장

읽기 시:
  - Feature flag에 따라 구/신 버전 키 선택
```

### 장점
- 즉시 롤백 가능
- 신/구 버전 서버 혼재 상황에서 안전

### 단점
- 메모리 2배 사용
- 쓰기 연산 2배
- 구현 복잡도 높음

### 사용 시나리오
- 여러 팀이 동시에 배포하는 MSA 환경
- 롤백 빈도가 높은 기능

---

## 전략 4: Gradual TTL Reduction (점진적 TTL 감소)

TTL을 점진적으로 줄여 자연스럽게 신버전으로 교체한다.

### 동작 원리

```
Day 1: TTL 5분 → 3분으로 감소
Day 2: TTL 3분 → 1분으로 감소
Day 3: 신버전 코드 배포, 정상 TTL 복구
```

### 장점
- 구현이 단순함
- 점진적 Hit rate 변화로 DB 부하 예측 가능

### 단점
- Hit rate가 점진적으로 하락
- DB에 어느 정도 부하가 감 (모니터링 필수)

### 사용 조건
```
현재 Hit Rate 70%
TTL 50% 감소 시 예상 Hit Rate: 55~60%
DB 장애 임계치: 50%

→ 여유 있음, 사용 가능
```

### 계산 예시

```
현재 상태:
  - TTL: 5분
  - Hit Rate: 70%
  - QPS: 10,000
  - Cache Miss → DB 쿼리: 3,000 QPS

TTL 50% 감소 후 예상:
  - TTL: 2.5분
  - 예상 Hit Rate: 55~60%
  - 예상 DB 쿼리: 4,000~4,500 QPS
  - DB 여유 용량 확인 필요!
```

---

## 전략 5: Shadow Mode (그림자 모드)

신버전 코드가 읽기/쓰기를 하되, 결과는 사용하지 않고 로그만 남긴다.

### 동작 원리

```
1. 구버전 로직으로 정상 처리 (실제 응답)
2. 신버전 로직도 병렬 실행 (결과 버림)
3. 두 결과 비교 → 불일치 시 로그/알림
4. 충분히 검증되면 신버전으로 전환
```

### 장점
- 프로덕션에서 실제 데이터로 검증
- 문제 발생 시 영향 없음

### 단점
- 리소스 2배 사용 (CPU, 네트워크)
- 구현 복잡도 높음

### 사용 시나리오
- 대규모 스키마 변경
- 높은 신뢰성이 요구되는 금융/결제 시스템

---

## 상황별 권장 전략

### 상황 1: 필드명 변경 (name → username)
```
권장: Lazy Migration
이유: 메모리 추가 없음, Hit rate 영향 없음
```

### 상황 2: 필드 추가 (기존 + newField)
```
권장: 하위호환 유지 (null 허용)
이유: 구버전 데이터도 읽을 수 있음, 마이그레이션 불필요

// 신버전 코드
public class UserProfile {
    private String name;
    private String newField; // nullable, 구버전엔 없음
}
```

### 상황 3: 필드 삭제 (기존 - removedField)
```
권장: 무시하고 배포
이유: JSON 역직렬화 시 unknown field 무시 설정

objectMapper.configure(
    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
);
```

### 상황 4: 데이터 타입 변경 (String → Integer)
```
권장: Versioned Key + Lazy Migration 조합
이유: 타입 변환 실패 위험, 명확한 버전 분리 필요
```

### 상황 5: 메모리 80%, 대규모 변경
```
권장: Lazy Migration (필수)
금지: Versioned Key, Dual Write (메모리 부족)
```

---

## 마이그레이션 체크리스트

### 배포 전

- [ ] 현재 Redis 메모리 사용량 확인
- [ ] 현재 Cache Hit Rate 확인
- [ ] DB 여유 용량 확인 (Hit Rate 하락 시 감당 가능?)
- [ ] 롤백 계획 수립
- [ ] 모니터링 대시보드 준비

### 배포 중

- [ ] Canary 배포로 일부 서버만 먼저 적용
- [ ] Hit Rate 실시간 모니터링
- [ ] DB 쿼리 수 모니터링
- [ ] 에러 로그 모니터링

### 배포 후

- [ ] 구버전 데이터 잔존 여부 확인
- [ ] 변환 로직 제거 시점 결정 (2~3 TTL 주기 후)
- [ ] 문서 업데이트

---

## 모니터링 지표

### 필수 모니터링

```
1. Cache Hit Rate (임계치: 50%)
2. Redis Memory Usage (임계치: 90%)
3. DB Query Count (기준 대비 증가율)
4. Application Error Rate
5. Response Time P99
```

### 알림 설정 예시

```yaml
alerts:
  - name: cache_hit_rate_critical
    condition: cache_hit_rate < 55%
    severity: critical
    action: 즉시 롤백 검토

  - name: cache_hit_rate_warning
    condition: cache_hit_rate < 65%
    severity: warning
    action: 모니터링 강화

  - name: redis_memory_high
    condition: redis_memory_usage > 85%
    severity: warning
    action: 불필요 키 정리 또는 스케일업 검토
```

---

## 롤백 시나리오

### Lazy Migration 롤백

```
1. 신버전 코드 롤백 배포
2. 이미 마이그레이션된 데이터는 구버전 코드에서 읽기 실패할 수 있음
3. 해결: 역변환 로직 추가 또는 해당 키들 무효화 (개별 삭제)
```

### Versioned Key 롤백

```
1. 신버전 키 사용 코드 롤백
2. 구버전 키가 그대로 존재하므로 즉시 정상화
3. 신버전 키는 TTL 만료로 자연 삭제
```

### 긴급 상황 (Hit Rate 급락)

```
1. 캐시 flush 금지! (DB 장애 유발)
2. 트래픽 일부 차단 (Rate Limiting)
3. 문제 서버 배포 롤백
4. DB 읽기 복제본 투입 (있다면)
```

---

## Anti-Patterns (하지 말아야 할 것)

### 1. 일괄 캐시 Flush
```
❌ redis.flushAll()  // DB 장애 유발
❌ redis.del("user:*")  // 대량 삭제도 위험
```

### 2. 빅뱅 배포
```
❌ 모든 서버 동시 신버전 배포
✅ Canary → 10% → 50% → 100% 점진적 배포
```

### 3. 변환 로직 없이 배포
```
❌ 신버전 코드만 배포 (구버전 캐시 역직렬화 실패)
✅ 변환 로직 포함하여 배포
```

### 4. 모니터링 없이 배포
```
❌ 배포 후 퇴근
✅ 배포 후 최소 30분~1시간 모니터링
```

---

## 결론

대부분의 캐시 스키마 변경은 **Lazy Migration**으로 해결 가능하다.

```
메모리 여유 없음 (80%) + 높은 Hit Rate 의존도 (50% 이하 시 장애)
= Lazy Migration이 유일한 안전한 선택
```

핵심 원칙:
1. **점진적으로** 마이그레이션
2. **모니터링하면서** 진행
3. **롤백 계획**을 항상 준비
4. **절대로** 일괄 삭제하지 않기
