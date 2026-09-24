local operation, expected, value = ARGV[1], ARGV[2], ARGV[3]
local now, deadline, identifier = tonumber(ARGV[4]), tonumber(ARGV[5]), ARGV[6]
if operation == 'CREATE' then
    if redis.call('EXISTS', KEYS[4]) == 1 then return 'EXPIRED' end
    if redis.call('EXISTS', KEYS[1]) == 1 then return 'EXISTS' end
    if deadline <= now or deadline > now + 900000 then return 'EXPIRED' end
    for index = 2, 3 do
        redis.call('ZREMRANGEBYSCORE', KEYS[index], '-inf', now)
        if redis.call('ZCARD', KEYS[index]) >= 4 then return 'FULL' end
    end
    redis.call('SET', KEYS[1], value, 'PXAT', deadline)
    for index = 2, 3 do
        redis.call('ZADD', KEYS[index], deadline, identifier)
        local latest = redis.call('ZRANGE', KEYS[index], -1, -1, 'WITHSCORES')
        redis.call('PEXPIREAT', KEYS[index], latest[2])
    end
    return 'OK'
end
if redis.call('GET', KEYS[1]) ~= expected then return 'EXPIRED' end
if operation == 'ACCEPT' then
    if deadline <= now then return 'EXPIRED' end
    redis.call('SET', KEYS[1], value, 'PXAT', deadline)
    return 'OK'
end
if operation == 'DELETE' then
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[5], KEYS[6], KEYS[7], KEYS[8])
    if deadline > now then redis.call('SET', KEYS[4], 'ended', 'PXAT', deadline) end
    redis.call('ZREM', KEYS[2], identifier)
    redis.call('ZREM', KEYS[3], identifier)
    return 'OK'
end
return 'INVALID'