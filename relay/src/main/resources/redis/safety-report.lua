local now, deadline = tonumber(ARGV[2]), tonumber(ARGV[3])
if deadline <= now or deadline > now + 2592000000 then return 'EXPIRED' end
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
if redis.call('ZCARD', KEYS[2]) >= 10000 then return 'FULL' end
if redis.call('EXISTS', KEYS[1]) == 1 then return 'CONFLICT' end
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', deadline)
redis.call('ZADD', KEYS[2], deadline, ARGV[4])
local latest = redis.call('ZRANGE', KEYS[2], -1, -1, 'WITHSCORES')
redis.call('PEXPIREAT', KEYS[2], latest[2])
return 'OK'
