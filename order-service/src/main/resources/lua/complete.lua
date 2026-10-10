local current = redis.call('HGET', KEYS[2], ARGV[1])
if current ~= ARGV[2] then return 0 end
redis.call('HSET', KEYS[3], ARGV[2], ARGV[3])
if ARGV[3] == 'REJECTED' then
  -- A DB stock mismatch is fail-closed; blindly returning inventory could repeat
  -- invalid admissions. The durable rejection remains queryable in MySQL.
  redis.call('HSET', KEYS[1], 'stock', 0)
  redis.call('HDEL', KEYS[2], ARGV[1])
end
return 1
