if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 'ENDED' end
local now, deadline, sessionDeadline = tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7])
if sessionDeadline <= now then return 'ENDED' end
local incoming = redis.call('GET', KEYS[2])
if incoming and ARGV[2] ~= '' and cjson.decode(incoming).id == ARGV[2] then
    redis.call('DEL', KEYS[2])
    incoming = false
end
if ARGV[3] ~= '' then
    if deadline <= now or deadline > sessionDeadline or deadline > now + 60000 then return 'ENDED' end
    local packet = cjson.decode(ARGV[3])
    local previous = redis.call('HGET', KEYS[4], packet.id)
    if previous and previous ~= ARGV[4] then return 'CONFLICT' end
    if not previous then
        if redis.call('HLEN', KEYS[4]) >= 10000 then return 'ENDED' end
        if redis.call('EXISTS', KEYS[3]) == 1 then return 'BUSY' end
        redis.call('SET', KEYS[3], ARGV[3], 'PXAT', deadline)
        redis.call('HSET', KEYS[4], packet.id, ARGV[4])
        redis.call('PEXPIREAT', KEYS[4], sessionDeadline)
    end
end
if incoming and cjson.decode(incoming).expiresAt <= now then
    redis.call('DEL', KEYS[2])
    incoming = false
end
return incoming or ''