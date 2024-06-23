package io.netty.example.chengtf.study.heima.rpc.consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.example.chengtf.study.heima.rpc.api.HelloService;
import io.netty.example.chengtf.study.heima.rpc.consumer.handler.RpcResponseMessageHandler;
import io.netty.example.chengtf.study.heima.rpc.consumer.message.RpcRequestMessage;
import io.netty.example.chengtf.study.heima._0common.protocol.MessageCodecSharable;
import io.netty.example.chengtf.study.heima._0common.protocol.ProcotolFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC客户端
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
@Slf4j
public class RpcClient {

    public static void main(String[] args) {
        NioEventLoopGroup group = new NioEventLoopGroup();
        LoggingHandler LOGGING_HANDLER = new LoggingHandler(LogLevel.INFO);
        MessageCodecSharable MESSAGE_CODEC = new MessageCodecSharable();
        RpcResponseMessageHandler RPC_HANDLER = new RpcResponseMessageHandler();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.group(group);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ProcotolFrameDecoder());
                    ch.pipeline().addLast(LOGGING_HANDLER);
                    ch.pipeline().addLast(MESSAGE_CODEC);
                    ch.pipeline().addLast(RPC_HANDLER);
                }
            });
            Channel channel = bootstrap.connect("localhost", 8080).sync().channel();
            channel.writeAndFlush(new RpcRequestMessage(
                    1,
                    HelloService.class.getName(),
                    "sayHello",
                    String.class,
                    new Class[]{String.class},
                    new Object[]{"成腾飞"}
            )).addListener(promise -> {
                if (!promise.isSuccess()) {
                    Throwable cause = promise.cause();
                    log.error("error", cause);
                }
            });
            channel.closeFuture().sync();
        } catch (Exception e) {
            log.error("client error", e);
        } finally {
            group.shutdownGracefully();
        }
    }

}
