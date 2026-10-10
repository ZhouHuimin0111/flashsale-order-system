package io.github.zhouhuimin.flashsale.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisConfig {
    @Bean(destroyMethod = "shutdown") public RedissonClient redisson(@Value("${spring.data.redis.host}") String host,
        @Value("${spring.data.redis.port}") int port, @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        var server = config.useSingleServer().setAddress("redis://" + host + ":" + port).setConnectionPoolSize(24).setConnectionMinimumIdleSize(4);
        if (!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }
    public static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        var script = new DefaultRedisScript<T>(); script.setLocation(new ClassPathResource("lua/" + path)); script.setResultType(type); return script;
    }
}
