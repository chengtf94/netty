package io.netty.example.chengtf.study.jksj.server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.example.chengtf.study.jksj._0common.Operation;
import io.netty.example.chengtf.study.jksj._0common.OperationResult;
import io.netty.example.chengtf.study.jksj._0common.RequestMessage;
import io.netty.example.chengtf.study.jksj._0common.ResponseMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * 点单服务业务处理器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Slf4j
public class OrderServerProcessHandler extends SimpleChannelInboundHandler<RequestMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RequestMessage requestMessage) throws Exception {
        Operation operation = requestMessage.getMessageBody();
        OperationResult operationResult = operation.execute();

        ResponseMessage responseMessage = new ResponseMessage();
        responseMessage.setMessageHeader(requestMessage.getMessageHeader());
        responseMessage.setMessageBody(operationResult);

        if (ctx.channel().isActive() && ctx.channel().isWritable()) {
            ctx.writeAndFlush(responseMessage);
        } else {
            log.error("not writable now, message dropped");
        }

    }


}
