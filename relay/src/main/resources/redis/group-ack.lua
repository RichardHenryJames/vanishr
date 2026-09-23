local value = redis.call('GET', KEYS[2])
if not value then return 'NOT_FOUND' end
local receipt = cjson.decode(value)
if receipt.expiresAt <= tonumber(ARGV[3]) then return 'EXPIRED' end
local state = receipt.recipients[ARGV[1]]
if ARGV[2] == 'DELETED' and receipt.senderDeviceId == ARGV[1] then
    for member, _ in pairs(receipt.recipients) do receipt.recipients[member] = 'DELETED' end
elseif not state then return 'FORBIDDEN'
elseif ARGV[2] == 'READ' or ARGV[2] == 'DELETED' then
    if state ~= 'DELETED' then receipt.recipients[ARGV[1]] = ARGV[2] end
elseif ARGV[2] == 'DELIVERED' then
    if state == 'QUEUED' then receipt.recipients[ARGV[1]] = 'DELIVERED' end
else return 'FORBIDDEN' end
local outstanding = false
for _, current in pairs(receipt.recipients) do
    if current == 'QUEUED' or (receipt.expiry == 'VIEW_ONCE' and current == 'DELIVERED') then outstanding = true end
end
if not outstanding then redis.call('DEL', KEYS[1], KEYS[3]) end
local updated = cjson.encode(receipt)
redis.call('SET', KEYS[2], updated, 'KEEPTTL')
return updated