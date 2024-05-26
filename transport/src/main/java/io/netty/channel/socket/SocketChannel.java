package io.netty.channel.socket;

import java.net.InetSocketAddress;

/**
 * SocketChannel：对应于客户端连接Socket，当客户端完成三次握手后，由系统调用accept函数根据监听Socket创建。
 */
public interface SocketChannel extends DuplexChannel {
    @Override
    ServerSocketChannel parent();
    @Override
    SocketChannelConfig config();
    @Override
    InetSocketAddress localAddress();
    @Override
    InetSocketAddress remoteAddress();
}
