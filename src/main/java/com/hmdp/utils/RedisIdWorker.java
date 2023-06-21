package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一Id生成器
 */

@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     * 2023.1.1.0.0.0
     */
    private static final long BEGIN_TIMESTAMP = 1672531200L;

    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2key自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接Id并返回
        /**
         * 时间戳位移序列号位数
         * 序列号或位填充
         */
        return timestamp << COUNT_BITS | count;
    }

    //public static void main(String[] args) {
    //    //初始时间戳
    //    LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
    //    long second = time.toEpochSecond(ZoneOffset.UTC);
    //    System.out.println("second = " + second);
    //
    //    //String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd:ss"));
    //    //System.out.println("format = " + format);
    //}
}
