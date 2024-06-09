/*
 * Copyright 2012 The Netty Project
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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChannelInitializer：一种特殊的ChannelInboundHandler，
 */
@Sharable
public abstract class ChannelInitializer<C extends Channel> extends ChannelInboundHandlerAdapter {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelInitializer.class);

    /**
     * ChannelInitializer实例是被所有的Channel共享的，用于初始化ChannelPipeline
     * initMap：通过Set集合保存已经初始化的ChannelPipeline，避免重复初始化同一ChannelPipeline
     */
    private final Set<ChannelHandlerContext> initMap = Collections.newSetFromMap(new ConcurrentHashMap<ChannelHandlerContext, Boolean>());

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isRegistered()) {
            // 初始化工作完成后，需要将自身从pipeline中移除
            if (initChannel(ctx)) {
                removeState(ctx);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean initChannel(ChannelHandlerContext ctx) throws Exception {
        if (initMap.add(ctx)) { // Guard against re-entrance.
            try {
                // 此时客户端NioSocketChannel已经创建并初始化好了
                initChannel((C) ctx.channel());
            } catch (Throwable cause) {
                exceptionCaught(ctx, cause);
            } finally {
                // 初始化完毕后，从pipeline中移除自身：当执行完initChannel 方法后，ChannelPipeline的初始化就结束了，此时就没必要再继续呆在pipeline中了
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.context(this) != null) {
                    pipeline.remove(this);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 匿名类实现，这里指定具体的初始化逻辑
     */
    protected abstract void initChannel(C ch) throws Exception;

    private void removeState(final ChannelHandlerContext ctx) {
        // 从initMap防重Set集合中删除ChannelInitializer
        if (ctx.isRemoved()) {
            initMap.remove(ctx);
        } else {
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    initMap.remove(ctx);
                }
            });
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public final void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // 当channelRegister事件发生时，调用initChannel初始化pipeline
        if (initChannel(ctx)) {
            ctx.pipeline().fireChannelRegistered();
            removeState(ctx);
        } else {
            // Called initChannel(...) before which is the expected behavior, so just forward the event.
            ctx.fireChannelRegistered();
        }
    }











    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (logger.isWarnEnabled()) {
            logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
        }
        ctx.close();
    }



    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        initMap.remove(ctx);
    }

}
