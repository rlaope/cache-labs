#!/bin/bash

# ============================================================
# 캐시 시스템 부하 테스트 스크립트
#
# 실제 HTTP 엔드포인트에 대한 부하 테스트
# ============================================================

set -e

# 설정
BASE_URL="${BASE_URL:-http://localhost:8080}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-1000}"
CONCURRENT="${CONCURRENT:-10}"

echo "=========================================="
echo "  캐시 시스템 부하 테스트"
echo "=========================================="
echo ""
echo "대상 URL: $BASE_URL"
echo "총 요청 수: $TOTAL_REQUESTS"
echo "동시 요청 수: $CONCURRENT"
echo ""

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 의존성 확인
check_dependencies() {
    echo -e "${BLUE}의존성 확인 중...${NC}"

    # curl 확인
    if ! command -v curl &> /dev/null; then
        echo -e "${RED}❌ curl이 설치되지 않았습니다.${NC}"
        exit 1
    fi

    # ab (Apache Bench) 확인
    if ! command -v ab &> /dev/null; then
        echo -e "${YELLOW}⚠️  Apache Bench(ab)가 설치되지 않았습니다.${NC}"
        echo "   설치: brew install httpd (macOS) 또는 apt install apache2-utils (Ubuntu)"
        USE_AB=false
    else
        USE_AB=true
    fi

    # wrk 확인
    if ! command -v wrk &> /dev/null; then
        echo -e "${YELLOW}⚠️  wrk가 설치되지 않았습니다.${NC}"
        echo "   설치: brew install wrk (macOS)"
        USE_WRK=false
    else
        USE_WRK=true
    fi

    echo ""
}

# 서버 상태 확인
check_server() {
    echo -e "${BLUE}서버 상태 확인 중...${NC}"

    if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null | grep -q "200"; then
        echo -e "${GREEN}✅ 서버가 정상 동작 중입니다.${NC}"
        return 0
    else
        echo -e "${RED}❌ 서버에 연결할 수 없습니다.${NC}"
        echo "   서버를 먼저 시작하세요: ./gradlew bootRun"
        return 1
    fi
}

# curl 기반 간단한 부하 테스트
run_curl_test() {
    local endpoint=$1
    local name=$2

    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} $name${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    local success=0
    local fail=0
    local total_time=0

    echo "요청 중..."

    for i in $(seq 1 $TOTAL_REQUESTS); do
        start_time=$(date +%s%N)
        status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL$endpoint" 2>/dev/null)
        end_time=$(date +%s%N)

        if [ "$status" == "200" ]; then
            ((success++))
        else
            ((fail++))
        fi

        elapsed=$(( (end_time - start_time) / 1000000 ))
        total_time=$((total_time + elapsed))

        # 진행 상황 표시 (100개마다)
        if [ $((i % 100)) -eq 0 ]; then
            echo -ne "\r  진행: $i / $TOTAL_REQUESTS"
        fi
    done

    echo ""
    echo ""
    echo "결과:"
    echo "  총 요청: $TOTAL_REQUESTS"
    echo "  성공: $success"
    echo "  실패: $fail"
    echo "  총 시간: ${total_time}ms"
    echo "  평균 응답 시간: $((total_time / TOTAL_REQUESTS))ms"
    echo "  처리량: $(echo "scale=2; $TOTAL_REQUESTS * 1000 / $total_time" | bc) req/s"
}

# Apache Bench 테스트
run_ab_test() {
    local endpoint=$1
    local name=$2

    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} $name (Apache Bench)${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    ab -n $TOTAL_REQUESTS -c $CONCURRENT "$BASE_URL$endpoint" 2>/dev/null | grep -E "(Requests per second|Time per request|Transfer rate|Complete requests|Failed requests)"
}

# wrk 테스트
run_wrk_test() {
    local endpoint=$1
    local name=$2
    local duration="${3:-10s}"

    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} $name (wrk - $duration)${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    wrk -t4 -c$CONCURRENT -d$duration "$BASE_URL$endpoint"
}

# 캐시 HIT 비율 테스트
test_cache_hit_ratio() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} 캐시 HIT 비율 테스트${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    # 테스트 데이터 생성
    echo "1. 테스트 데이터 생성 중..."
    for i in $(seq 1 10); do
        curl -s -X POST "$BASE_URL/api/reservations" \
            -H "Content-Type: application/json" \
            -d "{\"customerName\":\"TestUser$i\",\"resourceName\":\"Resource$i\",\"reservationTime\":\"2024-12-31T10:00:00\"}" > /dev/null
    done
    echo "   10개 예약 생성 완료"

    # 첫 번째 조회 (캐시 MISS)
    echo ""
    echo "2. 첫 번째 조회 (캐시 MISS 예상)..."
    start_time=$(date +%s%N)
    curl -s "$BASE_URL/api/reservations/1" > /dev/null
    end_time=$(date +%s%N)
    first_request_time=$(( (end_time - start_time) / 1000000 ))
    echo "   응답 시간: ${first_request_time}ms"

    # 두 번째 조회 (캐시 HIT)
    echo ""
    echo "3. 두 번째 조회 (캐시 HIT 예상)..."
    start_time=$(date +%s%N)
    curl -s "$BASE_URL/api/reservations/1" > /dev/null
    end_time=$(date +%s%N)
    second_request_time=$(( (end_time - start_time) / 1000000 ))
    echo "   응답 시간: ${second_request_time}ms"

    # 결과 분석
    echo ""
    echo "결과 분석:"
    if [ $second_request_time -lt $first_request_time ]; then
        improvement=$(echo "scale=1; ($first_request_time - $second_request_time) * 100 / $first_request_time" | bc)
        echo -e "  ${GREEN}✅ 캐시 HIT! 응답 시간 ${improvement}% 개선${NC}"
    else
        echo -e "  ${YELLOW}⚠️  캐시 효과가 미미합니다 (첫 요청이 이미 빨랐을 수 있음)${NC}"
    fi
}

# 동시 요청 테스트
test_concurrent_requests() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE} 동시 요청 테스트${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo ""

    echo "동시 요청 수: $CONCURRENT"
    echo ""

    # 동시 요청 실행
    for i in $(seq 1 $CONCURRENT); do
        curl -s "$BASE_URL/api/reservations/1" > /dev/null &
    done

    wait

    echo -e "${GREEN}✅ $CONCURRENT개 동시 요청 완료${NC}"
}

# 메인 메뉴
show_menu() {
    echo ""
    echo "테스트 유형을 선택하세요:"
    echo ""
    echo "  1) 캐시 HIT 비율 테스트"
    echo "  2) 동시 요청 테스트"
    echo "  3) 단일 엔드포인트 부하 테스트 (curl)"
    if [ "$USE_AB" = true ]; then
        echo "  4) Apache Bench 부하 테스트"
    fi
    if [ "$USE_WRK" = true ]; then
        echo "  5) wrk 부하 테스트"
    fi
    echo "  0) 종료"
    echo ""
}

# 메인 실행
main() {
    check_dependencies

    if ! check_server; then
        exit 1
    fi

    while true; do
        show_menu
        read -p "선택: " choice

        case $choice in
            1)
                test_cache_hit_ratio
                ;;
            2)
                test_concurrent_requests
                ;;
            3)
                read -p "엔드포인트 (기본: /api/reservations/1): " endpoint
                endpoint="${endpoint:-/api/reservations/1}"
                run_curl_test "$endpoint" "부하 테스트"
                ;;
            4)
                if [ "$USE_AB" = true ]; then
                    read -p "엔드포인트 (기본: /api/reservations/1): " endpoint
                    endpoint="${endpoint:-/api/reservations/1}"
                    run_ab_test "$endpoint" "Apache Bench 부하 테스트"
                else
                    echo -e "${RED}Apache Bench가 설치되지 않았습니다.${NC}"
                fi
                ;;
            5)
                if [ "$USE_WRK" = true ]; then
                    read -p "엔드포인트 (기본: /api/reservations/1): " endpoint
                    endpoint="${endpoint:-/api/reservations/1}"
                    read -p "테스트 시간 (기본: 10s): " duration
                    duration="${duration:-10s}"
                    run_wrk_test "$endpoint" "wrk 부하 테스트" "$duration"
                else
                    echo -e "${RED}wrk가 설치되지 않았습니다.${NC}"
                fi
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

main