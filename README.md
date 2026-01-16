# 캐시 시스템 설계

> 로컬 캐시 + Redis 이중 캐시 구조를 설계하며 정리한 내용

---

## 목차

1. [어떤 값을 캐싱할 것인가](#1-어떤-값을-캐싱할-것인가)
2. [어떻게 구현할 것인가](#2-어떻게-구현할-것인가)
3. [캐시 일관성을 어떻게 지킬 것인가](#3-캐시-일관성을-어떻게-지킬-것인가)
4. [분산 환경에서의 캐시 설계](#4-분산-환경에서의-캐시-설계)

---

## 1. 어떤 값을 캐싱할 것인가

### 1.1 로컬 캐시(Local Cache)에 적합한 데이터

로컬 캐시는 애플리케이션 메모리(Heap 등)에 데이터를 직접 저장한다. 네트워크 I/O가 없으므로 속도가 압도적으로 빠르지만, 서버 간 데이터 복제가 어렵다는 특징이 있다.

- **극도로 높은 빈도로 조회되는 'Hot' 데이터**: 모든 요청마다 참조해야 하는 설정값이나 공통 코드 데이터.
- **변경 빈도가 매우 낮은 정적 데이터**: 시스템의 비즈니스 로직에 영향을 주는 정책 설정, 국가 코드, 카테고리 목록 등.
- **서버 간 데이터 불일치가 치명적이지 않은 데이터**: 약간의 시차(TTL 기반)가 발생해도 사용자 경험에 큰 영향을 주지 않는 UI 레이아웃 정보 등.
- **Redis Hotspot 방지용 데이터**: 특정 키에 대한 요청이 너무 많아 Redis 한 대의 CPU 성능을 초과할 때(Cache Stampede), 이를 완화하기 위해 로컬에 2차 캐싱을 수행한다.

> 근데 Redisson 문서 보면 로컬 캐시 쓰면 읽기 성능이 최대 45배까지 빨라진다고 함. 물론 벤더 문서라 좀 과장 섞였을 수 있는데, 네트워크 I/O 없애는 게 얼마나 큰지는 체감됨. "A common misconception is that Redis is always the fastest caching option. In reality, in-process caching is faster than Redis" 라는 말도 있던데 Redis 맹신은 금물인듯 애초에 기술맹신자체가금물이긴함

### 1.2 Redis 캐시에 적합한 데이터

Redis는 분산 환경에서 여러 서버 인스턴스가 공유하는 저장소다. 데이터 정합성을 유지해야 하거나, 로컬 메모리에 담기에는 큰 데이터를 다룰 때 필수적이다.

- **공유 상태(Shared State) 정보**: 로그인 세션, 유저 토큰, 장바구니 정보처럼 사용자가 어떤 서버에 접속하더라도 동일하게 유지되어야 하는 데이터.
- **실시간 카운팅 및 랭킹**: 분산 환경에서 정확한 합산이 필요한 좋아요 수, 실시간 인기 검색어 순위(Sorted Set 활용).
- **DB 부하 경감용 고빈도 쿼리 결과**: 조회 쿼리 비용이 비싸지만 여러 사용자가 공통으로 사용하는 검색 결과나 게시글 상세 정보.
- **분산 락(Distributed Lock) 및 속도 제한(Rate Limiting)**: 여러 노드에서 동시에 접근하는 자원을 보호하거나, API 호출 횟수를 제한하기 위한 지표 데이터.


### 1.3 캐싱하면 안 되는 데이터

캐시는 '성능'을 위해 '정합성'을 희생하거나 '추가 비용'을 지불하는 행위다. 아래의 경우는 캐싱의 득보다 실이 크다.

- **실시간 정합성이 생명인 금융/결제 데이터**: 계좌 잔액이나 결제 상태 등 단 1ms의 불일치도 허용되지 않는 데이터는 항상 DB(Source of Truth)를 직접 참조해야 한다.
- **재사용성이 없는 일회성 데이터**: 단 한 번만 조회되고 다시 쓰이지 않는 데이터(예: 특정 검색 필터 일회용 결과). 캐시 히트율(Hit Ratio)이 낮아 메모리 낭비만 초래한다.
- **보안에 민감한 개인정보(암호화되지 않은 PII)**: 메모리 덤프나 Redis 노출 시 치명적인 개인정보는 캐싱을 피하거나 강력한 암호화가 선행되어야 한다.
- **자주 바뀌는데 읽기 빈도는 낮은 데이터**: 쓰기 작업 시마다 캐시를 갱신(Invalidation)해야 하므로 오버헤드만 발생시키고 읽기 이득은 없다.

> 유명한 격언 중에 "There are only two hard things in Computer Science: cache invalidation and naming things" 라는 게 있음. 농담반 진담반인데, 캐시 안 해도 될 걸 캐시하면 진짜 invalidation 지옥 맛봄. 히트율 낮으면 그냥 안 하는 게 맞다고 봄.

### 1.4 자주 변경되는 데이터 처리 전략

데이터 변경이 잦은 경우, '어느 시점에 캐시를 깨뜨릴 것인가(Invalidation)'와 '어떻게 DB와 동기화할 것인가'가 핵심이다.

**Write-Through / Write-Around / Write-Back:**
- **Write-Through**: DB와 캐시에 동시에 데이터를 쓴다. 정합성은 좋지만 쓰기 지연시간이 증가한다.
- **Write-Back (Write-Behind)**: 캐시에만 먼저 쓰고, 일정 주기나 이벤트에 따라 DB에 비동기로 반영한다. 쓰기 성능은 최고지만 서버 장애 시 데이터 유실 위험이 있다.

> CodeAhoy 글에서 "Write-back gives max performance at the cost of consistency risk, while write-through gives strong consistency at cost of performance. Decide what the priority is." 라고 정리해둠. 결국 트레이드오프인데, 일부 개발자들은 피크 타임 버퍼용으로 Redis에 Write-Back 쓰기도 한다더라. 근데 장애나면 데이터 날아가니까 중요한 데이터엔 비추.

**Cache Invalidation vs Update:**
- 데이터 변경 시 기존 캐시를 **삭제(Evict)** 하는 것이 일반적이다. 수정(Update) 방식은 레이스 컨디션(Race Condition)으로 인해 잘못된 데이터가 캐시에 남을 위험이 크다.

**TTL(Time To Live) 전략:**
- 자주 변경되는 데이터일수록 TTL을 짧게 가져가되, 캐시 유효기간 만료 시점에 대량의 요청이 DB로 몰리는 상황(Cache Stampede)을 방지하기 위해 만료 시간을 무작위(Jitter)로 설정하는 기법을 사용한다.


**CDC(Change Data Capture) 활용:**
- DB의 트랜잭션 로그를 감지하여 메시지 큐(Kafka 등)를 통해 비동기적으로 캐시를 갱신하는 방식으로, 애플리케이션 로직과 캐시 갱신 로직을 분리하여 확장성을 높인다.

### cc

**로컬 캐시 vs Redis 캐시**
- [Redis + Local Cache: Implementation and Best Practices](https://medium.com/@max980203/redis-local-cache-implementation-and-best-practices-f63ddee2654a) - 이중 캐시 구현 실전 가이드
- [Redis Cache vs. In-Memory Cache: When to Use What](https://blog.nashtechglobal.com/redis-cache-vs-in-memory-cache-when-to-use-what/) - 로컬/분산 캐시 선택 기준
- [Distributed Caching - Redis.io](https://redis.io/glossary/distributed-caching/) - Redis 공식 분산 캐시 문서

**캐시 쓰기 전략 (Write-Through / Write-Back / Write-Around)**
- [Caching Strategies and How to Choose the Right One](https://codeahoy.com/2017/08/11/caching-strategies-and-how-to-choose-the-right-one/) - 캐시 전략 비교 분석
- [Cache Invalidation Strategies - Design Gurus](https://www.designgurus.io/blog/cache-invalidation-strategies) - 캐시 무효화 전략 심층 가이드
- [Cache Invalidation and Methods - GeeksforGeeks](https://www.geeksforgeeks.org/system-design/cache-invalidation-and-the-methods-to-invalidate-cache/) - 캐시 무효화 방법론

**Cache Stampede (Thundering Herd)**
- [Thundering Herd / Cache Stampede](https://distributed-computing-musings.com/2021/12/thundering-herd-cache-stampede/) - 문제 정의와 해결책
- [Cache Stampede & The Thundering Herd Problem](https://medium.com/@sonal.sadafal/cache-stampede-the-thundering-herd-problem-d31d579d93fd) - 실전 사례와 대응 패턴
- [Thundering Herd - Ehcache Documentation](https://www.ehcache.org/documentation/2.8/recipes/thunderingherd.html) - BlockingCache를 활용한 해결

---

## 2. 어떻게 구현할 것인가

### 2.1 L1 + L2 이중 캐시 구조
TBD

### 2.2 Cache-Aside 패턴
TBD

### 2.3 TTL 설정 전략
TBD

### 2.4 캐시 키 설계
TBD

---

## 3. 캐시 일관성을 어떻게 지킬 것인가

### 3.1 캐시 무효화 전략
TBD

### 3.2 Write-Through vs Write-Behind
TBD

### 3.3 Cache Stampede 방지
TBD

### 3.4 로컬 캐시와 Redis 간 동기화
TBD

---

## 4. 분산 환경에서의 캐시 설계

### 4.1 Sticky Session 방식의 한계
TBD

### 4.2 Round-Robin / Random 라우팅에서의 문제점
TBD

### 4.3 분산 캐시 무효화 전략
TBD

### 4.4 Pub/Sub을 활용한 캐시 동기화
TBD

### 4.5 Consistent Hashing
TBD

---
