package com.hmdp.utils;

/**
 * @author 神様
 */
public interface ILock {

    /**
     * 获取锁
     * @param timeoutSec 设置锁的超时时间，过期自动释放
     * @return true代表获取成功，false代表获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
