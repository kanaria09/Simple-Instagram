package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信息
 * 手机号码、密码、昵称、头像、创建时间、更新时间
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 手机号码
     */
    private String phone;

    /**
     * 密码
     */
    private String password;

    /**
     * 昵称，默认随机
     */
    private String nickName;

    //头像
    private String icon = "";

    //创建时间
    private LocalDateTime createTime;

    //更新时间
    private LocalDateTime updateTime;


}
