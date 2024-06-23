package io.netty.example.chengtf.study.jksj.client.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.example.chengtf.study.jksj._0common.ResponseMessage;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * 协议解码器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderProtocolDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf byteBuf, List<Object> out) throws Exception {
        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.decode(byteBuf);
        out.add(responseMessage);
    }

}
