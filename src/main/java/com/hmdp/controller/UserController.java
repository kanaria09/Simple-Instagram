package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * 用户功能
 * 发送验证码、用户登录、登出
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    //发送手机验证码
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        //  发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        //  实现登录功能
        return userService.login(loginForm, session);
    }


    /**
     * 登出功能
     */
    @PostMapping("/logout")
    public Result logout(){
        // 从ThreadLocal中删除用户
        UserHolder.removeUser();
        //Result.fail("登出成功！");
        return Result.ok();
    }


    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 根据id查询用户
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /**
     * 用户签到功能
     */
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /**
     * 统计当前用户截止今天的连续签到天数
     */
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
