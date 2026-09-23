if redis.call('EXISTS', KEYS[3]) == 0 then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
if ARGV[3] == '' then
    redis.call('DEL', KEYS[2])
else
    redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
end
return 1