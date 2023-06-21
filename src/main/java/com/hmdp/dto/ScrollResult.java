package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动查询返回结果
 */
@Data
public class ScrollResult {
    //结果集合
    private List<?> list;
    //最小时间戳
    private Long minTime;
    //偏移量 当前查询最小值相同的元素个数
    private Integer offset;
}
