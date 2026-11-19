-- KEYS[1]: 当前维度的 key
-- ARGV[1]: 窗口毫秒数; ARGV[2]: 允许次数; ARGV[3]: 请求 UUID
-- 返回 0 表示允许；正整数表示至少需要等待的毫秒数
local key = KEYS[1]
local windowMs = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- 统一使用 Redis 时间，避免应用实例之间的时钟差异
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- 删除窗口之外的旧记录
redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)

-- ZCARD 返回有序集合中的成员数量
local count = redis.call('ZCARD', key)
if count >= limit then
    -- 配置下调后 count 可能大于 limit，需要等到足够多的记录过期
    local index = count - limit
    -- ZRANGE 在这里按分数从小到大排序，用下标取成员  这边取的是最早的请求
    local oldest = redis.call('ZRANGE', key, index, index, 'WITHSCORES')
    -- oldest[2] 表示返回结果中的“分数”，不是第二条请求
    return math.max(1, tonumber(oldest[2]) + windowMs - now)
end

redis.call('ZADD', key, now, ARGV[3])
redis.call('PEXPIRE', key, windowMs)
return 0
