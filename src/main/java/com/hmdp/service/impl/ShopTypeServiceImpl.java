package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE;

/**
 * 店铺类型
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopList() {
        // 1. 从redis中查询商铺类型列表
        //String jsonArray = stringRedisTemplate.opsForValue().get("shop-type");
        String jsonArray = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE);
        // json转list
        List<ShopType> jsonList = JSONUtil.toList(jsonArray,ShopType.class);
        //System.out.println("json"+jsonList);
        // 2. 命中，返回redis中商铺类型信息
        if (!CollectionUtils.isEmpty(jsonList)) {
            return Result.ok(jsonList);
        }
        // 3. 未命中，从数据库中查询商铺类型,并根据sort排序
        List<ShopType> shopTypesByMysql = query().orderByAsc("sort").list();
        //System.out.println("mysql"+shopTypesByMysql);
        // 4. 将商铺类型存入到redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE,JSONUtil.toJsonStr(shopTypesByMysql));
        // 5. 返回数据库中商铺类型信息
        return Result.ok(shopTypesByMysql);
    }
}
