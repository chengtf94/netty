package io.netty.channel.socket;

import io.netty.channel.ServerChannel;

import java.net.InetSocketAddress;

/**
 * ServerSocketChannel：对应于监听Socket，负责绑定监听端口地址，接收客户端连接并创建用于与客户端通信的SocketChannel。
 */
public interface ServerSocketChannel extends ServerChannel {
    @Override
    ServerSocketChannelConfig config();
    @Override
    InetSocketAddress localAddress();
    @Override
    InetSocketAddress remoteAddress();
}
