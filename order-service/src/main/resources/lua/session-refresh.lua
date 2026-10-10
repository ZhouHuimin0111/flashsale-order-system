local user = redis.call('HMGET', KEYS[1], 'id', 'username', 'role')
if not user[1] then return {} end
redis.call('EXPIRE', KEYS[1], ARGV[1])
return user
