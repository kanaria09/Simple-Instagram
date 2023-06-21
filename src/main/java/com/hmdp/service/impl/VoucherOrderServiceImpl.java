package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Var;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author 神様
 * 秒杀优惠券实现类
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取Redisson锁
     */
    @Resource
    private RedissonClient redissonClient;


    /**
     * 全局唯一Id生成
     */
    @Resource
    private RedisIdWorker redisIdWorker;



    //异步下单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 消息队列名
     */
    String queueName = "stream.orders";
    /**
     * 创建阻塞队列，并将订单信息保存入阻塞队列
     * 基于lua脚本语言实现
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //XREADGROUP g1 c1
                            Consumer.from("g1", "c1"),
                            //COUNT 1 BLOCK 2000
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //STREAMS streams.order >
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息后是否获取成功
                    if(list == null || list.isEmpty()){
                        //获取失败，队列中没有消息，继续下一轮循环
                        continue;
                    }
                    //3.从消息队列中解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，保存订单信息
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认，确保消息已经处理完成 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendinglist();
                }
            }
        }

        /**
         * 处理消息队列中出现异常的消息
         * PendingList
         */
        private void handlePendinglist() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            //XREADGROUP g1 c1
                            Consumer.from("g1", "c1"),
                            //COUNT 1
                            StreamReadOptions.empty().count(1),
                            //STREAMS streams.order 0
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息后是否获取成功
                    if(list == null || list.isEmpty()){
                        //获取失败，PendingList中没有异常消息，结束循环
                        break;
                    }
                    //3.从消息队列中解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，保存订单信息
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认，确保消息已经处理完成 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理PendingList订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 创建阻塞队列，并将订单信息保存入阻塞队列
     */
/*    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户Id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:");
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取成功
        if(!isLock){
            //获取失败
            log.error("订单异常");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //模拟延迟时间5000毫秒
            //Thread.sleep(5000);
            //释放锁
            lock.unlock();
        }
    }

    /**
     * 提前加载秒杀优惠券的lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    /**
     * 秒杀优惠券下单——基于lua脚本语言实现
     * 将订单信息写入stream消息队列
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //订单Id-全局唯一Id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                //为脚本传参数 优惠券Id、用户Id、订单Id
                voucherId.toString(), userId.toString() ,String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //不为0，没有购买资格
            return Result.fail(r ==1 ? "库存不足！" : "不能重复下单！");
        }
        //结果为0，有购买资格，将下单信息保存到消息队列
        //3.获取当前对象的代理对象(事务)，避免事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单Id
        return Result.ok(orderId);

    }


    /**
     * 秒杀优惠券下单——基于lua脚本语言实现
     * 将订单信息写入阻塞队列
     */
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户Id
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0，没有购买资格
            return Result.fail(r ==1 ? "库存不足！" : "不能重复下单！");
        }
        //2.2 为0，有购买资格，将下单信息保存到阻塞队列
        //2.3创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单Id-全局唯一Id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户Id
        voucherOrder.setUserId(userId);
        //代金券Id
        voucherOrder.setVoucherId(voucherId);
        //2.4放入阻塞队列
        orderTasks.add(voucherOrder);
        //3.获取当前对象的代理对象(事务)，避免事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        //3.返回订单Id
        return Result.ok(orderId);

    }*/


    /**
     * 秒杀优惠券下单——基于java语言实现
     */
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足！");
        }
        //根据userId开启锁
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:");
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取成功
        if(!isLock){
            //获取失败，返回错误或重试
            return Result.fail("当前用户已经购买过一次！");
        }
        try {
            //获取当前对象的代理对象(事务)，避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //模拟延迟时间5000毫秒
            //Thread.sleep(5000);
            //释放锁
            lock.unlock();
        }
    }*/

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5一人一单
        Long userId = voucherOrder.getUserId();
        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2判断订单是否唯一，优惠券Id与用户Id唯一
        if (count > 0) {
            //用户已经下过单
            log.error("订单异常");
            return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                // 库存减一
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                /**
                 * where id = ? and stock > 0
                 * 使用乐观锁解决超卖问题
                 */
                .gt("stock",0)
                .update();
        if (! success) {
            //扣减失败
            log.error("订单异常");
            return;
        }
        ////7.创建订单
        //VoucherOrder voucherOrder = new VoucherOrder();
        ////订单Id-全局唯一Id
        //long orderId = redisIdWorker.nextId("order");
        //voucherOrder.setId(orderId);
        ////用户Id
        //voucherOrder.setUserId(userId);
        ////代金券Id
        //voucherOrder.setVoucherId(voucherOrder);
        //保存订单信息
        save(voucherOrder);
    }
}
