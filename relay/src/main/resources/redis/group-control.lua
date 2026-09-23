local previous = redis.call('GET', KEYS[2])
if ARGV[1] == 'ACK' then
    if not previous then return 'NOT_FOUND' end
    if cjson.decode(previous).recipient ~= ARGV[7] then return 'FORBIDDEN' end
    redis.call('DEL', KEYS[1])
    redis.call('ZREM', KEYS[3], ARGV[6])
    return 'OK'
end
if tonumber(ARGV[5]) <= tonumber(ARGV[4]) then return 'EXPIRED' end
if previous then
    if cjson.decode(previous).digest ~= ARGV[3] then return 'CONFLICT' end
    return 'OK'
end
redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, ARGV[4])
if redis.call('ZCARD', KEYS[3]) >= (tonumber(ARGV[8]) or 512) then return 'FULL' end
redis.call('SET', KEYS[1], ARGV[2], 'PXAT', ARGV[5])
redis.call('SET', KEYS[2], cjson.encode({digest=ARGV[3],recipient=ARGV[7]}), 'PXAT', ARGV[5])
redis.call('ZADD', KEYS[3], ARGV[5], ARGV[6])
redis.call('PEXPIRE', KEYS[3], 86400000)
return 'OK'