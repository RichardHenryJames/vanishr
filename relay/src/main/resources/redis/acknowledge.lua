local stored = redis.call('GET', KEYS[2])
if not stored then return 'NOT_FOUND' end
local receipt = cjson.decode(stored)
local actor = ARGV[1]
local state = ARGV[2]
if state == 'DELETED' then
    if receipt.recipientDeviceId ~= actor and receipt.senderDeviceId ~= actor then return 'FORBIDDEN' end
elseif receipt.recipientDeviceId ~= actor then
    return 'FORBIDDEN'
end
if receipt.state == 'READ' or receipt.state == 'DELETED' then return stored end
if state ~= 'DELIVERED' and state ~= 'READ' and state ~= 'DELETED' then return 'CONFLICT' end
receipt.state = state
redis.call('SET', KEYS[2], cjson.encode(receipt), 'KEEPTTL')
redis.call('ZREM', 'inbox:' .. receipt.recipientDeviceId, receipt.id)
if state == 'READ' or state == 'DELETED' or receipt.expiry ~= 'VIEW_ONCE' then
    redis.call('DEL', KEYS[1])
    if receipt.mediaId ~= cjson.null then redis.call('DEL', 'b:' .. receipt.mediaId) end
end
return cjson.encode(receipt)