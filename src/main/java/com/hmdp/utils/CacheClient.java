package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Redis缓存工具类
 */

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将对象序列化为json储存在redis中，并设置过期时间
     * 写入redis时销毁时间加上0~9的随机数，防止缓存雪崩
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time + RandomUtil.randomLong(10), unit);
    }

    /**
     * 将对象序列化为json储存在redis中，并设置过期时间
     * 逻辑过期法
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据key查询缓存，并反序列化为指定类型
     * 利用缓存空值解决缓存穿透问题
     */
    public <R,ID> R queryWithPassThrough(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = KeyPrefix + id;
        //1.从redis查询商铺数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，返回商铺信息
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否为空值
        // json == ""
        if(json != null){
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if(r == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis,30分钟+0~9的随机数，防止redis缓存雪崩
        this.set(key, r,time,unit);
        //7.返回商铺信息
        return r;
    }

    /**
     * 根据key查询缓存，并反序列化为指定类型
     * 互斥锁解决缓存击穿
     */
    public <R,ID> R queryWithMutex(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = KeyPrefix + id;
        //1.从redis查询商铺数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在，返回商铺信息
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //判断命中的是否为空值
        // json == ""
        if(json != null){
            //返回错误信息
            return null;
        }
        //4.重建缓存
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(KeyPrefix,id,type,dbFallback,time,unit);
            }
            //4.4获取成功，根据id查询数据库
            r = dbFallback.apply(id);
            //Thread.sleep(5000);
            //5.不存在，返回错误
            if(r == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis,30分钟+0~9的随机数，防止redis缓存雪崩
            //stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r),CACHE_SHOP_TTL + RandomUtil.randomLong(10), TimeUnit.MINUTES);
            this.set(key, r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回商铺信息
        return r;
    }

    //缓存重建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将对象序列化为json储存在redis中，并设置过期时间
     * 逻辑过期解决缓存过期
     */
    public <R,ID> R queryWithLogicalExpire(String KeyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = KeyPrefix + id;
        //1.从redis查询商铺数据
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)){
            //3.不存在，返回null
            return null;
        }
        //4.命中，将Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断逻辑时间是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，返回店铺信息
            return r;
        }
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取成功
        if(isLock){
            //6.3获取成功，开启线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    //1.查询数据库
                    R r1 = dbFallback.apply(id);
                    //2.写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }  catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放互斥锁
                    unlock(lockKey);
                }
            });
        }
        //6.4获取失败，返回过期店铺信息
        return r;
    }


    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
