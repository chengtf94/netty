package io.netty.example.chengtf.study.jksj.server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.example.chengtf.study.jksj.common.Operation;
import io.netty.example.chengtf.study.jksj.common.OperationResult;
import io.netty.example.chengtf.study.jksj.common.RequestMessage;
import io.netty.example.chengtf.study.jksj.common.ResponseMessage;

/**
 * 点单服务业务处理器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class OrderServerProcessHandler extends SimpleChannelInboundHandler<RequestMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RequestMessage requestMessage) throws Exception {
        Operation operation = requestMessage.getMessageBody();
        OperationResult operationResult = operation.execute();

        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setMessageHeader(requestMessage.getMessageHeader());
        responseMessage.setMessageBody(operationResult);

        ctx.writeAndFlush(responseMessage);
    }


}
