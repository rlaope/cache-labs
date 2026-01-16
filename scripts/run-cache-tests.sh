#!/bin/bash

# ============================================================
# 캐시 시스템 테스트 실행 스크립트
#
# README에 정리된 캐시 전략들을 검증하는 테스트 모음
# ============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "  캐시 시스템 테스트 러너"
echo "=========================================="
echo ""

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_section() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} $1${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""
}

# 테스트 유형 선택
show_menu() {
    echo "실행할 테스트를 선택하세요:"
    echo ""
    echo "  1) 전체 테스트 실행"
    echo "  2) 분산 환경 시뮬레이션 테스트"
    echo "  3) Cache Stampede 테스트"
    echo "  4) Pub/Sub 캐시 동기화 테스트"
    echo "  5) Consistent Hashing 테스트"
    echo "  6) 성능 테스트"
    echo "  7) 특정 테스트 클래스 실행"
    echo "  0) 종료"
    echo ""
}

run_test() {
    local test_class=$1
    local test_name=$2

    print_section "$test_name"

    if [ -z "$test_class" ]; then
        ./gradlew test --info 2>&1 | grep -E "(테스트|===|결과|✅|📊|PASSED|FAILED)" || true
    else
        ./gradlew test --tests "$test_class" --info 2>&1 | grep -E "(테스트|===|결과|✅|📊|PASSED|FAILED|Node|키|분포)" || true
    fi

    if [ $? -eq 0 ]; then
        echo -e "\n${GREEN}✅ $test_name 완료${NC}"
    else
        echo -e "\n${RED}❌ $test_name 실패${NC}"
    fi
}

# Redis 상태 확인
check_redis() {
    print_section "Redis 연결 확인"

    if command -v redis-cli &> /dev/null; then
        if redis-cli ping > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Redis 연결됨${NC}"
        else
            echo -e "${YELLOW}⚠️  Redis가 실행 중이지 않습니다. Embedded Redis를 사용합니다.${NC}"
        fi
    else
        echo -e "${YELLOW}⚠️  redis-cli가 설치되지 않음. Embedded Redis를 사용합니다.${NC}"
    fi
}

# 메인 실행
main() {
    check_redis

    while true; do
        show_menu
        read -p "선택 (0-7): " choice

        case $choice in
            1)
                run_test "" "전체 테스트"
                ;;
            2)
                run_test "khope.cache.distributed.DistributedCacheSimulationTest" "분산 환경 시뮬레이션 테스트"
                ;;
            3)
                run_test "khope.cache.stampede.CacheStampedeTest" "Cache Stampede 테스트"
                ;;
            4)
                run_test "khope.cache.pubsub.PubSubCacheSyncTest" "Pub/Sub 캐시 동기화 테스트"
                ;;
            5)
                run_test "khope.cache.hashing.ConsistentHashingTest" "Consistent Hashing 테스트"
                ;;
            6)
                run_test "khope.cache.performance.CachePerformanceTest" "성능 테스트"
                ;;
            7)
                read -p "테스트 클래스명 입력: " class_name
                run_test "$class_name" "사용자 지정 테스트"
                ;;
            0)
                echo "종료합니다."
                exit 0
                ;;
            *)
                echo -e "${RED}잘못된 선택입니다.${NC}"
                ;;
        esac

        echo ""
        read -p "계속하려면 Enter를 누르세요..."
    done
}

# 인자가 있으면 해당 테스트만 실행
if [ ! -z "$1" ]; then
    case $1 in
        "all")
            run_test "" "전체 테스트"
            ;;
        "distributed")
            run_test "khope.cache.distributed.DistributedCacheSimulationTest" "분산 환경 시뮬레이션 테스트"
            ;;
        "stampede")
            run_test "khope.cache.stampede.CacheStampedeTest" "Cache Stampede 테스트"
            ;;
        "pubsub")
            run_test "khope.cache.pubsub.PubSubCacheSyncTest" "Pub/Sub 캐시 동기화 테스트"
            ;;
        "hashing")
            run_test "khope.cache.hashing.ConsistentHashingTest" "Consistent Hashing 테스트"
            ;;
        "performance")
            run_test "khope.cache.performance.CachePerformanceTest" "성능 테스트"
            ;;
        *)
            run_test "$1" "사용자 지정 테스트"
            ;;
    esac
else
    main
fi
