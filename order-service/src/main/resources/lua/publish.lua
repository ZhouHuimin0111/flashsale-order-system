if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
-- Stream/group must exist before admission; seed record is ignored by the relay.
redis.call('XADD', KEYS[2], '*', 'type', 'seed')
redis.call('HSET', KEYS[1], 'stock', ARGV[1], 'startsAt', ARGV[2], 'endsAt', ARGV[3])
return 1
