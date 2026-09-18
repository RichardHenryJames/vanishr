local stored = redis.call('GET', KEYS[1])
if not stored then return 'NOT_FOUND' end
local media = cjson.decode(stored)
if media.senderDeviceId ~= ARGV[1] then return 'FORBIDDEN' end
if media.messageId ~= cjson.null then return 'CONFLICT' end
redis.call('DEL', KEYS[1])
return 'OK'