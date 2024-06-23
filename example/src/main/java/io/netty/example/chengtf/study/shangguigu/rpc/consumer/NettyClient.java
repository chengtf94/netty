package io.netty.example.chengtf.study.shangguigu.rpc.consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Netty客户端
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class NettyClient {

    /**
     * 创建线程池
     */
    private static ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
    );
    private static NettyClientHandler clientHandler;
    private int count = 0;

    /**
     * 编写方法使用代理模式，获取一个代理对象
     */
    public Object getBean(final Class<?> serivceClass, final String providerName) {
        return Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{serivceClass}, (proxy, method, args) -> {

                    System.out.println("(proxy, method, args) 进入...." + (++count) + " 次");

                    // 初始化客户端
                    if (clientHandler == null) {
                        initClient();
                    }

                    // 设置要发给服务器端的信息
                    // providerName 协议头 args[0] 就是客户端调用api hello(???), 参数
                    clientHandler.setPara(providerName + args[0]);

                    //
                    return executor.submit(clientHandler).get();

                });
    }

    /**
     * 初始化客户端
     */
    private static void initClient() {
        clientHandler = new NettyClientHandler();
        // 创建EventLoopGroup
        NioEventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(clientHandler);
                    }
                });

        try {
            bootstrap.connect("127.0.0.1", 7000).sync();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
