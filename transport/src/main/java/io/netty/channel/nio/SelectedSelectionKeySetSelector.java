package io.netty.channel.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;

/***
 * 装饰类：将unwrappedSelector和与sun.nio.ch.SelectorImpl类关联好的Netty优化实现SelectedSelectionKeySet封装装饰起来。
 * wrappedSelector会将所有对Selector的操作全部代理给unwrappedSelector，并在发起轮询IO事件的相关操作中，重置SelectedSelectionKeySet清空上一次的轮询结果。
 */
final class SelectedSelectionKeySetSelector extends Selector {
    /**
     * Netty优化后的 SelectedKey就绪集合
     */
    private final SelectedSelectionKeySet selectionKeys;
    /**
     * 优化后的JDK NIO 原生Selector
     */
    private final Selector delegate;

    SelectedSelectionKeySetSelector(Selector delegate, SelectedSelectionKeySet selectionKeys) {
        this.delegate = delegate;
        this.selectionKeys = selectionKeys;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public SelectorProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<SelectionKey> keys() {
        return delegate.keys();
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
        return delegate.selectedKeys();
    }

    @Override
    public int selectNow() throws IOException {
        selectionKeys.reset();
        return delegate.selectNow();
    }

    @Override
    public int select(long timeout) throws IOException {
        // 重置SelectedKeys集合
        selectionKeys.reset();
        return delegate.select(timeout);
    }

    @Override
    public int select() throws IOException {
        // 重置SelectedKeys集合
        selectionKeys.reset();
        return delegate.select();
    }

    @Override
    public Selector wakeup() {
        return delegate.wakeup();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
