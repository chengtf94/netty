package io.netty.example.chengtf.study.heima._0common.protocol;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 序号ID生成器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public abstract class SequenceIdGenerator {

    private static final AtomicInteger id = new AtomicInteger();

    public static int nextId() {
        return id.incrementAndGet();
    }

}
