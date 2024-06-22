package io.netty.example.studyjksj.client.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.example.studyjksj.common.RequestMessage;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * 协议编码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderProtocolEncoder extends MessageToMessageEncoder<RequestMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, RequestMessage requestMessage, List<Object> out) throws Exception {
        ByteBuf buffer = ctx.alloc().buffer();
        requestMessage.encode(buffer);
        out.add(buffer);
    }

}
