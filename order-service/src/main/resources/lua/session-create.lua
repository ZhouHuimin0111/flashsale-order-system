redis.call('HSET', KEYS[1], 'id', ARGV[1], 'username', ARGV[2], 'role', ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
