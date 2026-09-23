local message = cjson.decode(ARGV[1])
local receipt = cjson.decode(ARGV[2])
local now = tonumber(ARGV[4])
if receipt.expiresAt <= now then return 'EXPIRED' end
local previous = redis.call('GET', KEYS[2])
if previous then
    if cjson.decode(previous).digest ~= receipt.digest then return 'CONFLICT' end
    return previous
end
redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, now)
if redis.call('ZCARD', KEYS[3]) >= 256 then return 'FULL' end
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', receipt.expiresAt)
if ARGV[3] ~= '' then redis.call('SET', KEYS[4], ARGV[3], 'PXAT', receipt.expiresAt) end
redis.call('SET', KEYS[2], ARGV[2], 'PXAT', receipt.expiresAt)
redis.call('ZADD', KEYS[3], receipt.expiresAt, message.id)
redis.call('PEXPIRE', KEYS[3], 86400000)
return ARGV[2]