package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jdk.internal.org.objectweb.asm.tree.analysis.Value;
import org.aspectj.weaver.ast.Var;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import sun.security.provider.certpath.PKIXTimestampParameters;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 查看热点笔记
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据Id查看博客详情
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        //2.查询blog相关的用户
        queryBlogUser(blog);
        //3.查看笔记是否已经被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查看笔记是否已被点赞
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取当前登录用户id
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞功能
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null) {
            // 3，如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2，保存用户到Redis的set集合 zadd key value score
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.将用户从Redis的set集台移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 在店在哪列表显示5名用户
     * 根据点赞时间排序
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 04
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户信息  WHERE id IN(5,1) ORDER BY FIELD(5,1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    //保存探店笔记
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        //2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("笔记保存失败！");
        }
        //3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记给所有粉丝
        for (Follow follow : follows) {
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2推送笔记
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 关注用户笔记滚动显示
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4.解析数据 blogI, minTme(时间戳),offset(偏移量)
        List<Long> ids = new ArrayList<Long>(typedTuples.size());
        //初始时间戳
        long minTime = 0;
        //初始偏移量
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        //5.根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5.1查询blog相关的用户
            queryBlogUser(blog);
            //5.2查看笔记是否已经被点赞
            isBlogLiked(blog);
        }
        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    /**
     * 查询博客用户信息，并将信息保存入博客信息
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
