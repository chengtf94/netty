package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server：创建主从Reactor线程组
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)                             // 设置主从Reactor（线程模型）
                    .channel(NioServerSocketChannel.class)                     // 设置主Reactor中的channel类型
                    .option(ChannelOption.SO_BACKLOG, 100)               // 设置主Reactor中的channel的option选项
                    .handler(new LoggingHandler(LogLevel.INFO))                // 设置主Reactor中的Channel->pipeline->handler
                    .childHandler(new ChannelInitializer<SocketChannel>() {    // 设置从Reactor中注册channel的pipeline
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {                // 设置从Reactor中的Channel->pipeline->handler
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }
                            p.addLast(new LoggingHandler(LogLevel.INFO));
                            p.addLast(serverHandler);
                        }
                    })
                    // 两种设置keep-alive风格
//                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(NioChannelOption.SO_KEEPALIVE, true)
            ;

            // Start the server：绑定端口启动服务，开始监听accept事件
            ChannelFuture f = b.bind(PORT).sync();

            // Wait until the server socket is closed：等待服务端NioServerSocketChannel关闭，作用是让程序不会退出
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads：优雅关闭主从Reactor线程组里的所有Reactor线程，结束main方法
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
