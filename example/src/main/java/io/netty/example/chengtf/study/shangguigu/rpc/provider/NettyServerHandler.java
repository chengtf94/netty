package io.netty.example.chengtf.study.shangguigu.rpc.provider;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.example.chengtf.study.shangguigu.rpc.consumer.ClientBootstrap;
import io.netty.example.chengtf.study.shangguigu.rpc.provider.HelloServiceImpl;
import io.netty.example.chengtf.study.shangguigu.rpc.provider.ServerBootstrap;

/**
 * Netty服务端：业务处理器
 *
 * @author: chengtf
 * @date: 2024/6/17
 */
public class NettyServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //获取客户端发送的消息，并调用服务
        System.out.println("msg=" + msg);
        //客户端在调用服务器的api 时，我们需要定义一个协议
        //比如我们要求 每次发消息是都必须以某个字符串开头 "HelloService#hello#你好"
        if (msg.toString().startsWith(ServerBootstrap.providerName)) {
            String result = new HelloServiceImpl().hello(msg.toString().substring(msg.toString().lastIndexOf("#") + 1));
            ctx.writeAndFlush(result);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }
}
