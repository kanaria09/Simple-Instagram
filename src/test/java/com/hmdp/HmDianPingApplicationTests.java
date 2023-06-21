package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.mysql.jdbc.TimeUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    //Redis缓存工具类
    @Resource
    private CacheClient cacheClient;

    //全局唯一ID生成器
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws Exception {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    /**
     * 提前保存店铺信息，并设置逻辑过期时间
     * 逻辑过期时间解决redis缓存击穿
     */
    @Test
    void testSaveShop() throws InterruptedException {
        //shopService.SaveShopRedis(1l,10l);
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop,10L, TimeUnit.SECONDS);

    }

    /**
     * 搜索附近的店铺
     * 提前加载店铺信息到redis
     */
    @Test
    void LoadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.将店铺按typeId分组，类型一致的保存到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取类型ID
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //3.2获取同类型额店铺集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //3.3写入redis GEOADD key 经度 纬度 mamber
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                            shop.getId().toString(),
                            new Point(shop.getX(), shop.getY())
                        ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }

    /**
     * UV统计
     */
    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int CO = 0;
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            CO++;
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999) {
                //发送到redis
                stringRedisTemplate.opsForHyperLogLog().add("hl", values);
            }
        }
        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl");
        System.out.println("count = " + count);
        System.out.println("CO = " + CO);
    }
}
