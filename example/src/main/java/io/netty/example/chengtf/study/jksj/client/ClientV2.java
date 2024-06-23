package io.netty.example.chengtf.study.jksj.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.example.chengtf.study.jksj.client.codec.*;
import io.netty.example.chengtf.study.jksj.client.handler.dispatcher.OperationResultFuture;
import io.netty.example.chengtf.study.jksj.client.handler.dispatcher.RequestPendingCenter;
import io.netty.example.chengtf.study.jksj.client.handler.dispatcher.ResponseDispatcherHandler;
import io.netty.example.chengtf.study.jksj._0common.RequestMessage;
import io.netty.example.chengtf.study.jksj._0common.order.OrderOperation;
import io.netty.example.chengtf.study.jksj._0common.util.IdUtil;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.util.concurrent.ExecutionException;

/**
 * 点单客户端：极客时间案例
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class ClientV2 {

    public static void main(String[] args) throws InterruptedException, ExecutionException {

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(NioSocketChannel.class);

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            bootstrap.group(group);
            final RequestPendingCenter requestPendingCenter = new RequestPendingCenter();

            bootstrap.handler(new ChannelInitializer<NioSocketChannel>() {
                @Override
                protected void initChannel(NioSocketChannel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new OrderFrameDecoder());
                    pipeline.addLast(new OrderFrameEncoder());

                    pipeline.addLast(new OrderProtocolEncoder());
                    pipeline.addLast(new OrderProtocolDecoder());

                    pipeline.addLast(new ResponseDispatcherHandler(requestPendingCenter));

                    pipeline.addLast(new OperationToRequestMessageEncoder());

                    pipeline.addLast(new LoggingHandler(LogLevel.INFO));
                }
            });

            ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 8090);

            channelFuture.sync();

            long streamId = IdUtil.nextId();

            RequestMessage requestMessage = new RequestMessage(
                    streamId, new OrderOperation(1001, "tudou"));

            OperationResultFuture operationResultFuture = new OperationResultFuture();

            requestPendingCenter.add(streamId, operationResultFuture);

            channelFuture.channel().writeAndFlush(requestMessage);

            System.out.println("操作结果为：" + operationResultFuture.get());

            channelFuture.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

}
