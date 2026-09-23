if redis.call('EXISTS', KEYS[2], KEYS[3]) ~= 0 then return 0 end
if ARGV[1] ~= '' then
    local previous = redis.call('GET', KEYS[1])
    if previous ~= ARGV[1] then return 0 end
    local session = cjson.decode(previous)
    redis.call('DEL', session.accessKey, KEYS[1])
end
redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[4])
redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[5])
return 1