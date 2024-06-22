package io.netty.example.chengtf.study.jksj.server.codec;


import io.netty.handler.codec.LengthFieldPrepender;

/**
 * 帧编码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderFrameEncoder extends LengthFieldPrepender {
    public OrderFrameEncoder() {
        super(2);
    }
}
