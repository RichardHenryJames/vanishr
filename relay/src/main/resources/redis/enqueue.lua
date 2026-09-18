local message = cjson.decode(ARGV[1])
local receipt = cjson.decode(ARGV[2])
local now = tonumber(ARGV[3])
if message.expiresAt <= now then return 'EXPIRED' end
local previous = redis.call('GET', KEYS[2])
if previous then
    local existing = cjson.decode(previous)
    if existing.senderDeviceId ~= receipt.senderDeviceId or existing.digest ~= receipt.digest then return 'CONFLICT' end
    return previous
end
if message.mediaId ~= cjson.null then
    local uploaded = redis.call('GET', KEYS[5])
    if not uploaded then return 'NOT_FOUND' end
    local media = cjson.decode(uploaded)
    if media.senderDeviceId ~= message.senderDeviceId or media.recipientDeviceId ~= message.recipientDeviceId then return 'FORBIDDEN' end
    if media.messageId ~= cjson.null or media.expiresAt ~= message.expiresAt then return 'CONFLICT' end
    media.messageId = message.id
    redis.call('SET', KEYS[5], cjson.encode(media), 'PXAT', message.expiresAt)
end
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', message.expiresAt)
redis.call('SET', KEYS[2], ARGV[2], 'PXAT', message.expiresAt)
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
redis.call('ZADD', KEYS[3], message.expiresAt, message.id)
redis.call('PEXPIRE', KEYS[3], 86400000)
redis.call('ZADD', KEYS[4], message.expiresAt, message.id)
redis.call('PEXPIRE', KEYS[4], 86400000)
return ARGV[2]