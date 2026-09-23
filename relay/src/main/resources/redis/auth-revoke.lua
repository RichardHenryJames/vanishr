local value = redis.call('GET', KEYS[1])
if not value then return 0 end
local session = cjson.decode(value)
if type(session.refreshKey) == 'string' then redis.call('DEL', session.refreshKey) end
return redis.call('DEL', KEYS[1])