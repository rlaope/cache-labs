# 캐시 시스템 테스트 가이드

> 이 문서는 캐시 시스템의 테스트 구조, 각 테스트가 검증하는 내용, 그리고 왜 이렇게 설계했는지를 설명한다.

---

## 목차

1. [테스트 아키텍처 개요](#1-테스트-아키텍처-개요)
2. [테스트 구조 설계 원칙](#2-테스트-구조-설계-원칙)
3. [테스트별 상세 가이드](#3-테스트별-상세-가이드)
4. [테스트 실행 스크립트](#4-테스트-실행-스크립트)
5. [테스트 환경 설정](#5-테스트-환경-설정)

---

## 1. 테스트 아키텍처 개요

### 1.1 디렉토리 구조

```
src/test/java/khope/cache/
├── config/
│   └── EmbeddedRedisConfig.java    # 테스트용 Embedded Redis 설정
├── distributed/
│   └── DistributedCacheSimulationTest.java  # 분산 환경 시뮬레이션
├── stampede/
│   └── CacheStampedeTest.java      # Cache Stampede 문제/해결책 테스트
├── pubsub/
│   └── PubSubCacheSyncTest.java    # Pub/Sub 캐시 동기화 테스트
├── hashing/
│   ├── ConsistentHashRing.java     # Consistent Hashing 구현체
│   └── ConsistentHashingTest.java  # Consistent Hashing 검증
└── performance/
    └── CachePerformanceTest.java   # 성능 벤치마크 테스트

scripts/
├── run-cache-tests.sh              # 테스트 실행 스크립트
└── load-test.sh                    # HTTP 부하 테스트 스크립트
```

### 1.2 테스트와 README 섹션 매핑

| 테스트 패키지 | README 섹션 | 검증 목적 |
|-------------|------------|----------|
| `distributed` | 2.1 L1+L2 이중 캐시 구조 | 이중 캐시 조회 흐름이 설계대로 동작하는지 |
| `stampede` | 3.3 Cache Stampede 방지 | Stampede 문제 재현 및 해결책 효과 검증 |
| `pubsub` | 3.4, 4.4 Pub/Sub 동기화 | 분산 환경에서 캐시 일관성 유지 검증 |
| `hashing` | 4.5 Consistent Hashing | 노드 변경 시 최소 재배치 검증 |
| `performance` | 전체 | 캐시 전략의 실제 성능 효과 측정 |

---

## 2. 테스트 구조 설계 원칙

### 2.1 왜 패키지를 기능별로 분리했는가?

```
❌ 안 좋은 구조: 모든 테스트를 하나의 클래스에
✅ 선택한 구조: 검증 목적별로 패키지 분리
```

**이유:**

1. **관심사 분리**: 각 테스트가 README의 특정 섹션과 1:1 매핑되어, 이론을 실제로 검증하는 구조
2. **독립 실행 가능**: 특정 기능만 빠르게 테스트할 수 있음 (`./gradlew test --tests "*StampedeTest"`)
3. **유지보수 용이**: 새로운 캐시 전략 추가 시 해당 패키지만 확장

### 2.2 왜 통합 테스트(SpringBootTest)를 사용했는가?

```java
@SpringBootTest
@ActiveProfiles("test")
class DistributedCacheSimulationTest { ... }
```

**이유:**

- 캐시 시스템은 여러 컴포넌트(CacheManager, RedisTemplate, Service)가 협력하는 구조
- 단위 테스트로는 실제 캐시 동작(L1→L2→DB 흐름)을 검증하기 어려움
- Embedded Redis를 사용해 실제 Redis와 유사한 환경에서 테스트

### 2.3 왜 시뮬레이션 방식을 사용했는가?

실제 분산 환경(여러 서버)을 테스트 환경에서 구축하기는 어렵다. 대신:

```java
// 여러 노드를 스레드로 시뮬레이션
ExecutorService executor = Executors.newFixedThreadPool(nodeCount);
for (int node = 0; node < nodeCount; node++) {
    executor.submit(() -> {
        // 각 스레드가 하나의 서버 노드처럼 동작
    });
}
```

**장점:**
- 별도 인프라 없이 분산 환경 시나리오 테스트 가능
- 동시성 문제(Race Condition, Stampede)를 쉽게 재현
- CI/CD 파이프라인에서 자동 실행 가능

---

## 3. 테스트별 상세 가이드

### 3.1 분산 환경 시뮬레이션 테스트 (`distributed/`)

**검증 목표:**
- L1 → L2 → DB 순서로 캐시를 조회하는지
- L2 HIT 시 L1에 복사되는지
- 여러 노드가 동시 접근해도 캐시가 효과적인지

**테스트 케이스:**

| 테스트 메서드 | 검증 내용 |
|-------------|----------|
| `l1CacheHit_shouldNotAccessL2` | L1 HIT 시 L2/DB 접근 없이 바로 반환 |
| `l1Miss_l2Hit_shouldCopyToL1` | L2 HIT 시 데이터를 L1에 복사 |
| `bothMiss_shouldLoadFromDbAndCacheBoth` | 모두 MISS 시 DB 조회 후 양쪽에 캐싱 |
| `multipleNodes_concurrentAccess` | 10개 노드, 100요청씩 동시 접근 시 DB 호출 최소화 |

**왜 이 테스트가 중요한가:**

README 2.1에서 설명한 "조회 흐름"이 실제로 동작하는지 증명한다:
```
애플리케이션 → L1 확인 → L2 확인 → DB 확인
```

### 3.2 Cache Stampede 테스트 (`stampede/`)

**검증 목표:**
- Stampede 문제가 실제로 발생하는지 재현
- Distributed Lock, Jitter, PER 등 해결책의 효과 검증

**테스트 케이스:**

| 테스트 메서드 | 검증 내용 |
|-------------|----------|
| `cacheExpiry_stampede_problemSimulation` | 캐시 만료 시 DB 폭주 문제 재현 (의도적 실패) |
| `distributedLock_preventStampede` | Lock으로 DB 호출을 1회로 제한 |
| `ttlJitter_preventSimultaneousExpiry` | Jitter로 만료 시점 분산 효과 확인 |
| `probabilisticEarlyRecomputation_simulation` | PER 알고리즘 동작 시뮬레이션 |
| `hotKey_trafficConcentration` | Hot Key에 대한 캐시 효과 측정 |

**왜 "문제 상황"도 테스트하는가:**

```java
@Test
@DisplayName("캐시 만료 시 Stampede 현상 시뮬레이션 (문제 상황)")
void cacheExpiry_stampede_problemSimulation() {
    // 이 테스트는 의도적으로 문제를 보여줌
    assertThat(dbCallCount.get()).isGreaterThan(1);  // DB가 여러 번 호출됨 = 문제!
}
```

- 문제를 먼저 재현해야 해결책의 효과를 증명할 수 있음
- "왜 Stampede 방지가 필요한가?"에 대한 실증적 근거 제공

### 3.3 Pub/Sub 캐시 동기화 테스트 (`pubsub/`)

**검증 목표:**
- Redis Pub/Sub을 통한 L1 캐시 무효화 브로드캐스팅
- 메시지 유실 시나리오 및 TTL 보완 전략

**테스트 케이스:**

| 테스트 메서드 | 검증 내용 |
|-------------|----------|
| `pubSubInvalidation_broadcastToAllNodes` | 무효화 메시지가 모든 노드에 전달되는지 |
| `dataUpdate_invalidateAllNodesL1Cache` | 데이터 수정 시 모든 노드 L1 삭제 |
| `pubSubMessageLoss_shortTtlCompensation` | 메시지 유실 시 짧은 TTL로 자동 보완 |
| `bulkInvalidation_pubSubPerformance` | 대량 무효화 시 Pub/Sub 처리량 측정 |

**왜 "SimulatedNode" 클래스를 만들었는가:**

```java
static class SimulatedNode {
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();

    public void subscribeToInvalidationChannel(String channel) {
        // Redis Pub/Sub 구독
    }
}
```

- 실제 분산 환경의 여러 서버를 하나의 JVM 내에서 시뮬레이션
- 각 노드가 독립적인 L1 캐시와 메시지 수신 상태를 가짐
- 메시지 유실 시나리오를 쉽게 재현 가능

### 3.4 Consistent Hashing 테스트 (`hashing/`)

**검증 목표:**
- 노드 추가/제거 시 최소한의 키만 재배치되는지
- 가상 노드 수에 따른 분포 균등도
- 전통적 해싱(hash % N) 대비 개선 효과

**테스트 케이스:**

| 테스트 메서드 | 검증 내용 |
|-------------|----------|
| `basicHashRing_operation` | 기본 해시 링 동작 검증 |
| `nodeAddition_minimalKeyRelocation` | 노드 추가 시 ~25%만 재배치 (vs 전통 ~75%) |
| `nodeRemoval_minimalKeyRelocation` | 노드 제거 시 최소 재배치 |
| `virtualNodes_distributionBalance` | 가상 노드 1개 vs 200개 분포 비교 |
| `traditionalVsConsistentHashing_comparison` | 전통 해싱 vs Consistent Hashing 비교표 |
| `failureScenario_nodeFailureAndRecovery` | 장애/복구 시나리오 시뮬레이션 |

**왜 ConsistentHashRing 구현체를 테스트 코드에 포함했는가:**

```java
// src/test/java/khope/cache/hashing/ConsistentHashRing.java
public class ConsistentHashRing<T> {
    private final ConcurrentSkipListMap<Long, T> ring = new ConcurrentSkipListMap<>();
    // ...
}
```

- README에 작성한 알고리즘을 실제로 검증하기 위함
- 프로덕션 코드에는 Redis Cluster나 라이브러리를 사용하므로 테스트용으로만 구현
- 알고리즘의 동작 원리를 코드로 이해할 수 있음

### 3.5 성능 테스트 (`performance/`)

**검증 목표:**
- L1 vs L2 vs DB 응답 시간 차이 측정
- 캐시 HIT 비율과 처리량 관계
- 동시성 환경에서의 성능 특성

**테스트 케이스:**

| 테스트 메서드 | 검증 내용 |
|-------------|----------|
| `compareResponseTimes_L1vsL2vsDB` | L1/L2/DB 응답 시간 비교 (P50, P95, P99) |
| `measureCacheHitRatio_variousPatterns` | Zipf 분포 요청에서 HIT 비율 측정 |
| `concurrencyPerformance_throughputByThreads` | 스레드 1~32개에 따른 처리량 변화 |
| `cacheWarmup_performanceMeasurement` | 순차 vs 병렬 워밍업 성능 비교 |
| `memoryUsage_largeCacheSize` | 캐시 크기에 따른 메모리 사용량 |
| `mixedWorkload_readWritePerformance` | 읽기 90% / 쓰기 10% 혼합 워크로드 |

**왜 다양한 워크로드 패턴을 테스트하는가:**

```java
// Zipf 분포: 일부 키에 요청이 집중 (실제 트래픽 패턴과 유사)
int keyIndex = (int) Math.floor(Math.pow(random.nextDouble(), 2) * uniqueKeys);
```

- 실제 서비스에서는 균등 분포가 아닌 편향된 분포가 발생
- Hot Key 문제, 캐시 효율성 등을 현실적으로 측정하기 위함

---

## 4. 테스트 실행 스크립트

### 4.1 run-cache-tests.sh

**목적:** 테스트를 쉽게 선택하고 실행할 수 있는 인터랙티브 스크립트

**사용법:**

```bash
# 메뉴 선택형 실행
./scripts/run-cache-tests.sh

# 특정 테스트 직접 실행
./scripts/run-cache-tests.sh all        # 전체 테스트
./scripts/run-cache-tests.sh distributed # 분산 환경 테스트
./scripts/run-cache-tests.sh stampede   # Stampede 테스트
./scripts/run-cache-tests.sh pubsub     # Pub/Sub 테스트
./scripts/run-cache-tests.sh hashing    # Consistent Hashing 테스트
./scripts/run-cache-tests.sh performance # 성능 테스트
```

**왜 셸 스크립트로 만들었는가:**

1. `./gradlew test --tests "..."` 명령어가 길고 기억하기 어려움
2. 테스트 결과에서 핵심 정보만 필터링하여 출력
3. CI/CD 파이프라인에서 특정 테스트만 실행할 때 유용

### 4.2 load-test.sh

**목적:** 실제 HTTP 엔드포인트에 대한 부하 테스트

**지원 도구:**
- `curl`: 기본 부하 테스트 (설치 불필요)
- `ab` (Apache Bench): 동시 요청 테스트
- `wrk`: 고성능 벤치마크

**테스트 시나리오:**

| 시나리오 | 검증 내용 |
|---------|----------|
| 캐시 HIT 비율 테스트 | 첫 요청(MISS) vs 두 번째 요청(HIT) 응답 시간 비교 |
| 동시 요청 테스트 | 여러 요청이 동시에 들어와도 정상 처리되는지 |
| 엔드포인트 부하 테스트 | 특정 API의 처리량(req/s) 측정 |

**왜 HTTP 부하 테스트가 별도로 필요한가:**

- JUnit 테스트는 내부 메서드를 직접 호출 (실제 네트워크 경로 X)
- HTTP 테스트는 Controller → Service → Cache 전체 경로 검증
- 실제 프로덕션과 유사한 환경에서의 성능 측정

---

## 5. 테스트 환경 설정

### 5.1 application-test.yml

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6370  # 실제 Redis(6379)와 충돌 방지
      timeout: 1000ms

cache:
  local:
    expire-seconds: 10   # 테스트용 짧은 TTL
    maximum-size: 100
  redis:
    expire-seconds: 30
```

**왜 테스트용 설정을 분리했는가:**

1. **포트 분리**: Embedded Redis(6370)와 로컬 Redis(6379) 충돌 방지
2. **짧은 TTL**: TTL 만료 테스트를 빠르게 수행하기 위함
3. **작은 캐시 크기**: 메모리 사용량 테스트에서 빠른 확인

### 5.2 EmbeddedRedisConfig

```java
@Configuration
@Profile("test")
public class EmbeddedRedisConfig {
    @PostConstruct
    public void startRedis() {
        redisServer = RedisServer.builder()
                .port(redisPort)
                .setting("maxmemory 128M")
                .build();
        redisServer.start();
    }
}
```

**왜 Embedded Redis를 사용하는가:**

1. **독립 실행**: 외부 Redis 없이 테스트 가능
2. **CI/CD 호환**: GitHub Actions 등에서 별도 설정 없이 실행
3. **격리된 환경**: 테스트 간 데이터 오염 방지

### 5.3 테스트 의존성

```kotlin
// build.gradle.kts
testImplementation("it.ozimov:embedded-redis:0.7.3")  // Embedded Redis
testImplementation("org.awaitility:awaitility:4.2.0") // 비동기 테스트
testImplementation("org.openjdk.jmh:jmh-core:1.37")   // 벤치마크
```

| 의존성 | 용도 |
|-------|-----|
| embedded-redis | 테스트용 Redis 서버 |
| awaitility | Pub/Sub 메시지 수신 대기 등 비동기 검증 |
| jmh | 마이크로 벤치마크 (추후 확장용) |

---

## 부록: 테스트 실행 체크리스트

### 로컬 개발 환경

```bash
# 1. 전체 테스트 실행
./gradlew test

# 2. 테스트 리포트 확인
open build/reports/tests/test/index.html

# 3. 특정 테스트만 실행 (디버깅 시)
./gradlew test --tests "*ConsistentHashingTest" --info
```

### CI/CD 파이프라인

```yaml
# GitHub Actions 예시
- name: Run Cache Tests
  run: ./gradlew test

- name: Upload Test Report
  uses: actions/upload-artifact@v3
  with:
    name: test-report
    path: build/reports/tests/test/
```

### 성능 테스트 (프로덕션 배포 전)

```bash
# 1. 애플리케이션 실행
./gradlew bootRun &

# 2. 부하 테스트 실행
./scripts/load-test.sh

# 3. wrk로 상세 벤치마크
wrk -t4 -c100 -d30s http://localhost:8080/api/reservations/1
```

---

## 참고 자료

- [README.md](../README.md) - 캐시 시스템 설계 문서
- [Spring Cache 공식 문서](https://docs.spring.io/spring-framework/reference/integration/cache.html)
- [Caffeine Cache Wiki](https://github.com/ben-manes/caffeine/wiki)
- [Redis Pub/Sub](https://redis.io/docs/manual/pubsub/)
