package io.netty.example.studyjksj.server.codec;


import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 帧解码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderFrameDecoder extends LengthFieldBasedFrameDecoder {
    public OrderFrameDecoder() {
        super(Integer.MAX_VALUE, 0, 2, 0, 2);
    }
}
