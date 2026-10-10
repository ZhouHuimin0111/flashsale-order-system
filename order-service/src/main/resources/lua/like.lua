local liked
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  redis.call('ZREM', KEYS[1], ARGV[1])
  liked = '0'
else
  local time = redis.call('TIME')
  redis.call('ZADD', KEYS[1], tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000), ARGV[1])
  liked = '1'
end
local count = redis.call('ZCARD', KEYS[1])
redis.call('ZADD', KEYS[2], count, ARGV[2])
return {liked, tostring(count)}
