local media = cjson.decode(ARGV[1])
local now = tonumber(ARGV[2])
if media.expiresAt <= now then return 'EXPIRED' end
local previous = redis.call('GET', KEYS[1])
if previous then
    local existing = cjson.decode(previous)
    if existing.senderDeviceId ~= media.senderDeviceId or existing.recipientDeviceId ~= media.recipientDeviceId
        or existing.digest ~= media.digest or existing.expiresAt ~= media.expiresAt then return 'CONFLICT' end
    return 'OK'
end
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', math.min(media.expiresAt, now + 300000))
return 'OK'