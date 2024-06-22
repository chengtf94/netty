package io.netty.example.chengtf.study.shangguigu.rpc.provider;

/**
 * ServerBootstrap 会启动一个服务提供者，就是 NettyServer
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class ServerBootstrap {

    public static final String providerName = "HelloService#hello#";

    public static void main(String[] args) {
        NettyServer.startServer("127.0.0.1", 7000);
    }

}
