package io.netty.example.studyjksj.server.codec;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.example.studyjksj.common.ResponseMessage;
import io.netty.handler.codec.MessageToMessageEncoder;

/**
 * 协议编码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderProtocolEncoder extends MessageToMessageEncoder<ResponseMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ResponseMessage responseMessage, List<Object> out) throws Exception {
        ByteBuf buffer = ctx.alloc().buffer();
        responseMessage.encode(buffer);
        out.add(buffer);
    }

}
