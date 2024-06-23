package io.netty.example.chengtf.study.heima._0common.protocol;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 帧解码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class ProcotolFrameDecoder extends LengthFieldBasedFrameDecoder {

    public ProcotolFrameDecoder() {
        this(1024, 12, 4, 0, 0);
    }

    public ProcotolFrameDecoder(int maxFrameLength, int lengthFieldOffset, int lengthFieldLength, int lengthAdjustment, int initialBytesToStrip) {
        super(maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip);
    }
}
