package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.lang.reflect.Field;
import java.nio.file.CopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    /**
     * 生成验证码
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1。校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.若不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到 redis, 有效期2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送验证码成功，验证码:{}",code);
        //6.返回处理结果
        return Result.ok();
    }

    /**
     * 登录
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.若不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.从redis获取验证码并校验
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cachecode == null || !cachecode.equals(code)) {
            //3.不一致，报错
            return Result.fail("验证码错误！");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if(user == null){
            //6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到redis,只保存用户基本信息
        //7.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //7.2将user对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //7.3将用户信息保存到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4设置token有效期, 30分钟+0~9的随机数，防止redis缓存雪崩
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL + RandomUtil.randomLong(10), TimeUnit.MINUTES);

        //8.返回token
        return Result.ok(token);
    }

    ///**
    // * 登出功能
    // */
    //@Override
    //public Result logout() {
    //    //获取当前登录用户ID
    //    Long userId = UserHolder.getUser().getId();
    //    // 1.根据token获取user信息
    //    String tokenKey = LOGIN_USER_KEY + token;
    //    Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(tokenKey);
    //    if (entries == null || entries.isEmpty()) {
    //        // 2. token不存在，登出失败
    //        return Result.fail("登出失败！");
    //    }
    //    // 3. 存在，则删除用户信息
    //    stringRedisTemplate.delete(tokenKey);
    //    // 4. 返回登出成功的信息
    //    return Result.ok("登出成功！");
    //}

    /**
     * 用户签到
     */
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy-MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取当前是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth -1, true);
        return Result.ok();
    }

    /**
     * 统计当前用户本月截止今天的连续签到天数
     * 从最后一天起，判断最后一次未签到
     */
    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy-MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取当前是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录，返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result == null || result.isEmpty()) {
            //没有任何签到记录
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok();
        }
        //6.循环遍历
        int count = 0;
        while (true) {
            //将数字与1做与运算，得到数字的最后一位bit位,判断bit位是否为0
            if((num & 1) == 0){
                //为0，未签到，循环结束
                break;
            }else {
                //不为0，已签到，统计天数+1
                count++;
            }
            //将数字右移一位，改变最后一位bit位，继续循环
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 使用手机号创建用户
     */
    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户
        save(user);
        return user;
    }
}
