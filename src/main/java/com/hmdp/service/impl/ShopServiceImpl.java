package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import com.sun.corba.se.spi.orbutil.fsm.Guard;
import com.sun.net.httpserver.Authenticator;
import lombok.Lombok;
import lombok.experimental.PackagePrivate;
import org.apache.ibatis.annotations.Results;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;
import org.xml.sax.EntityResolver;
import sun.security.provider.certpath.PKIXTimestampParameters;

import javax.annotation.Resource;
import javax.lang.model.element.VariableElement;

import java.beans.Transient;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 缓存工具类
     */
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

        if(shop == null){
            return Result.fail("店铺信息不存在！");
        }
        //7.返回商铺信息
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决缓存击穿-已封装工具类
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺数据
        String shopjson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopjson)){
            //3.存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopjson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        // shopjson == ""
        if(shopjson != null){
            //返回错误信息
            return null;
        }
        //4.重建缓存
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3获取失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4获取成功，根据id查询数据库
            shop = getById(id);
            //Thread.sleep(5000);
            //5.不存在，返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis,30分钟+0~9的随机数，防止redis缓存雪崩
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + RandomUtil.randomLong(10), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回商铺信息
        return shop;
    }

    /**
     * 缓存穿透-已封装工具类
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，返回商铺信息
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        // shopjson == ""
        if(shopJson != null){
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if(shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，写入redis,30分钟+0~9的随机数，防止redis缓存雪崩
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL + RandomUtil.randomLong(10), TimeUnit.MINUTES);
        //7.返回商铺信息
        return shop;
    }

    //缓存重建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存过期
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //3.不存在，返回null
            return null;
        }
        //4.命中，将shopJson反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断逻辑时间是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，返回店铺信息
            return shop;
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
                    this.SaveShopRedis(id,20L);
                }  catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放互斥锁
                    unlock(lockKey);
                }
            });
        }
        //6.4获取失败，返回过期店铺信息
        return shop;
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

    /**
     * 提前保存店铺信息，逻辑过期法
     */
    public void SaveShopRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺信息
        Shop shop = getById(id);
        //模拟延迟时间
        Thread.sleep(5000);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 更新店铺信息
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不存在");
        }
        String key = CACHE_SHOP_KEY + id;
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(key);
        //3.放回结果
        return Result.ok();
    }

    /**
     * 按id查询店铺
     * 按距离、类型查询附近店铺
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
