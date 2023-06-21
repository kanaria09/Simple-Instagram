package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author 获取、释放分布式锁
 * 基于lua脚本实现
 */
public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 提前加载释放锁的lua脚本
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 锁名前缀
     */
    private static final String KEY_PREFIX = "lock:";

    /**
     * 使用UUID拼接线程ID
     * 添加线程标识，解决锁误删问题
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 基于Lua脚本实现释放锁功能
     * 释放锁由lua脚本命令实现，保证事务的原子性
     */
    @Override
    public void unlock() {
        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }

    /**
     * 判断与释放可能因为堵塞无法保证原子性
     */
    //@Override
    //public void unlock() {
    //    //获取线程标识
    //    String threadId = ID_PREFIX + Thread.currentThread().getId();
    //    //获取锁中的标识
    //    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    //判断标识是否一致
    //    if (threadId.equals(id)) {
    //        //释放锁
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}
