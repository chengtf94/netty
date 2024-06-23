package io.netty.example.chengtf.study.jksj._0common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ID生成器工具类
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public final class IdUtil {

    private static final AtomicLong IDX = new AtomicLong();

    private IdUtil(){
        //no instance
    }

    public static long nextId(){
        return IDX.incrementAndGet();
    }

}
