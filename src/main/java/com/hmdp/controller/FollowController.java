package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 关注相关
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注 or 取关
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }

    /**
     * 判断是否已经关注
     */
    @GetMapping("/or/not/{id}")
    public Result isfollow(@PathVariable("id") Long followUserId){
        return followService.isfollow(followUserId);
    }

    /**
     * 共同关注
     */
    @GetMapping("/common/{id}")
    public Result followCommon(@PathVariable("id") Long id){
        return followService.followCommon(id);
    }



}
