package io.netty.example.chengtf.study.jksj.client.handler.dispatcher;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.example.chengtf.study.jksj._0common.ResponseMessage;

public class ResponseDispatcherHandler extends SimpleChannelInboundHandler<ResponseMessage> {

    private RequestPendingCenter requestPendingCenter;

    public ResponseDispatcherHandler(RequestPendingCenter requestPendingCenter) {
        this.requestPendingCenter = requestPendingCenter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ResponseMessage responseMessage) throws Exception {
        requestPendingCenter.set(responseMessage.getMessageHeader().getStreamId(), responseMessage.getMessageBody());
    }

}
