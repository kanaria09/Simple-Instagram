package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Redisson客户端
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        //配置服务器
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        //创建RedissonClient对象
        return Redisson.create(config);

    }
}
