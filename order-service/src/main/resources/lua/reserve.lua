-- All keys are explicitly passed and share a coupon hash tag.
local inventory = redis.call('HMGET', KEYS[1], 'stock', 'startsAt', 'endsAt')
if not inventory[1] then return {'NOT_READY'} end
local existing = redis.call('HGET', KEYS[2], ARGV[1])
if existing then
  return {'DUPLICATE', existing, redis.call('HGET', KEYS[3], existing) or 'PENDING'}
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < tonumber(inventory[2]) then return {'NOT_STARTED'} end
if now > tonumber(inventory[3]) then return {'ENDED'} end
if tonumber(inventory[1]) <= 0 then return {'SOLD_OUT'} end
-- Lua is atomic, but does not roll back on runtime errors. All key types are owned
-- by this service; no eviction, no manual edits, and sufficient memory are required.
redis.call('XADD', KEYS[5], '*', 'orderId', ARGV[2], 'userId', ARGV[1], 'couponId', ARGV[3])
redis.call('HINCRBY', KEYS[1], 'stock', -1)
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[3], ARGV[2], 'PENDING')
redis.call('HSET', KEYS[4], ARGV[2], ARGV[1])
return {'ACCEPTED', ARGV[2]}
