/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

public interface ChannelInboundInvoker {

    /**
     * 传播 ChannelRegistered 事件
     * 1）当 main reactor 在启动的时候，NioServerSocketChannel 会被创建并初始化，随后就会向main reactor注册，当注册成功后就会在 NioServerSocketChannel 中的 pipeline 中传播 ChannelRegistered 事件。
     * 2）当 main reactor 接收客户端发起的连接后，NioSocketChannel 会被创建并初始化，随后会向 sub reactor 注册，当注册成功后会在 NioSocketChannel 中的 pipeline 传播 ChannelRegistered 事件。
     */
    ChannelInboundInvoker fireChannelRegistered();

    /**
     * 传播 ChannelUnregistered 事件
     */
    ChannelInboundInvoker fireChannelUnregistered();

    /**
     * 传播 ChannelActive 事件
     * 1）当 NioServerSocketChannel 再向 main reactor 注册成功并触发 ChannelRegistered 事件传播之后，随后就会在 pipeline 中触发 bind 事件，
     * 而 bind 事件是一个 outbound 事件，会从 pipeline 中的尾结点 TailContext 一直向前传播最终在 HeadContext 中执行真正的绑定操作。
     * 当 netty 服务端 NioServerSocketChannel 绑定端口成功之后，才算是真正的 Active ，随后触发 ChannelActive 事件在 pipeline 中的传播。
     * 2）客户端 NioSocketChannel 中触发 ChannelActive 事件就会比较简单，当 NioSocketChannel 再向 sub reactor 注册成功并触发 ChannelRegistered 之后，
     * 紧接着就会触发 ChannelActive 事件在 pipeline 中传播。
     */
    ChannelInboundInvoker fireChannelActive();

    /**
     * 传播 ChannelInActive 事件
     */
    ChannelInboundInvoker fireChannelInactive();

    /**
     * 传播 ExceptionCaught 事件
     */
    ChannelInboundInvoker fireExceptionCaught(Throwable cause);

    /**
     * 传播 UserEventTriggered 事件：允许用户自定义异步事件，这样可以使得用户能够灵活的定义各种复杂场景的处理机制
     */
    ChannelInboundInvoker fireUserEventTriggered(Object event);

    /**
     * ChannelRead事件：一次循环读取一次数据，就触发一次ChannelRead事件。
     * Netty服务端对于一次OP_READ事件的处理，会在一个do{}while()循环read loop中分多次从客户端NioSocketChannel中读取网络数据，
     * 每次读取我们分配的ByteBuffer容量大小，初始容量为2048。
     * 1）当客户端有新连接请求的时候，服务端的 NioServerSocketChannel 上的 OP_ACCEPT 事件会活跃，随后 main reactor 会在一个 read loop 中不断的调用
     * serverSocketChannel.accept() 接收新的连接直到全部接收完毕或者达到 read loop 最大次数 16 次。
     * 在 NioServerSocketChannel 中，每 accept 一个新的连接，就会在 pipeline 中触发 ChannelRead 事件。
     * 一个完整的 read loop 结束之后，会触发 ChannelReadComplete 事件。
     * 2）当客户端 NioSocketChannel 上有请求数据到来时，NioSocketChannel 上的 OP_READ 事件活跃，随后 sub reactor 也会在一个 read loop 中对
     * NioSocketChannel 中的请求数据进行读取直到读取完毕或者达到 read loop 的最大次数 16 次。
     * 在 read loop 的读取过程中，每读取一次就会在 pipeline 中触发 ChannelRead 事件。当一个完整的 read loop 结束之后，
     * 会在 pipeline 中触发 ChannelReadComplete 事件。
     */
    ChannelInboundInvoker fireChannelRead(Object msg);

    /**
     * ChannelReadComplete事件：当读取不到数据或者不满足continueReading的任意一个条件就会退出read loop，
     * 这时就会触发ChannelReadComplete事件，表示本次OP_READ事件处理完毕
     */
    ChannelInboundInvoker fireChannelReadComplete();

    /**
     * 传播 ChannelWritabilityChanged 事件
     */
    ChannelInboundInvoker fireChannelWritabilityChanged();

}
