package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.sun.corba.se.pept.encoding.OutputObject;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.lang.model.element.VariableElement;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 登录拦截器
 * @author 神様
 */
public class LoginInterceptor implements HandlerInterceptor {
    /**
     * 登录拦截器
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断是否需要拦截（ThreadLocal中是否有用户）
        if(UserHolder.getUser() == null){
            //用户不存在，返回401状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        //用户存在，放行
        return true;
    }
}
