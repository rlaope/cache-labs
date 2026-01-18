-- migrate_user.lua
-- 유저 프로필 스키마 마이그레이션: name → username
--
-- KEYS[1] = 변환할 키 (예: user:123)
-- 반환값:
--   1  = 변환 완료
--   0  = 이미 신버전 (변환 불필요)
--   -1 = 키 없음
--   -2 = JSON 파싱 실패

local key = KEYS[1]
local data = redis.call('GET', key)

-- 키가 없으면 -1 반환
if not data then
    return -1
end

-- JSON 파싱 시도
local ok, obj = pcall(cjson.decode, data)
if not ok then
    return -2
end

-- 이미 신버전이면 스킵 (username 필드 존재)
if obj['username'] ~= nil then
    return 0
end

-- 구버전 감지 (name 필드 존재, username 없음)
if obj['name'] ~= nil then
    -- name → username 변환
    obj['username'] = obj['name']
    obj['name'] = nil

    -- 신버전 JSON 생성
    local newData = cjson.encode(obj)

    -- 기존 TTL 유지하면서 저장
    local ttl = redis.call('TTL', key)
    if ttl > 0 then
        redis.call('SETEX', key, ttl, newData)
    elseif ttl == -1 then
        -- TTL 없음 (영구 키)
        redis.call('SET', key, newData)
    else
        -- TTL이 -2면 키가 없는 것 (이미 체크했으므로 여기 안 옴)
        redis.call('SET', key, newData)
    end

    return 1
end

-- name도 username도 없는 경우 (다른 형식의 데이터)
return 0
