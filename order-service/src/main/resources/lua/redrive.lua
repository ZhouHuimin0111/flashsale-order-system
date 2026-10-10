local owner = redis.call('HGET', KEYS[1], ARGV[1])
if not owner or redis.call('HGET', KEYS[2], ARGV[1]) ~= 'PENDING' then return 0 end
redis.call('XADD', KEYS[3], '*', 'orderId', ARGV[1], 'userId', owner, 'couponId', ARGV[2])
return 1
