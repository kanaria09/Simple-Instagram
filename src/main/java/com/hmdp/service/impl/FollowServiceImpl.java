package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注 or 取关
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //2.判断关注或取关操作
        if (isFollow){
            //3.关注操作，增加数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                //将关注的用户id保存入redis中
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            //4.取关操作，删除数据 delete tb_from follow where user_id = ? and follow_user_id = ?
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id",followUserId));
            //将关注的用户从redis中移除
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    /**
     * 判断当前用户是否已经关注
     */
    @Override
    public Result isfollow(Long followUserId) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询是否已经关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.判断
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     */
    @Override
    public Result followCommon(Long id) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //当前用户id
        String key1 = "follow:" + userId;
        //查询的用户id
        String key2 = "follow:" + id;
        //2.查找交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()) {
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
