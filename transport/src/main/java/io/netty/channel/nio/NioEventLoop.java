package io.netty.channel.nio;

import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于IO模型的EventLoop：Netty会自动根据操作系统以及版本的不同选择对应的IO多路复用技术实现。
 * 比如Linux 2.6版本以上用的是Epoll，2.6版本以下用的是Poll，Mac下采用的是Kqueue。
 */
public final class NioEventLoop extends SingleThreadEventLoop {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    /**
     * Selector优化开关：通过系统变量-D io.netty.noKeySetOptimization指定，默认是开启的，表示需要对JDK NIO原生Selector进行优化。
     * 为了遍历的效率，会对Selector中的SelectedKeys进行数据结构优化
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    /**
     * Selector自动重建阈值
     */
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;
    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * unwrappedSelector&SelectedSelectionKeySet装饰类、优化后的JDK NIO 原生Selector： NIO多路复用选择器
     */
    private Selector selector;
    private Selector unwrappedSelector;
    /**
     * 通过反射替换selector对象中的selectedKeySet保存就绪的selectKey，该字段为持有selector对象selectedKeys的引用，当IO事件就绪时，直接从这里获取
     */
    private SelectedSelectionKeySet selectedKeys;
    /**
     * 创建JDK NIO Selector,ServerSocketChannel
     */
    private final SelectorProvider provider;
    /**
     * 下一次唤醒纳秒数：
     *     //    AWAKE            when EL is awake，表示当前Reactor正处于苏醒状态
     *     //    NONE             when EL is waiting with no wakeup scheduled，表示当前Reactor正处于等待状态
     *     //    other value T    when EL is waiting with wakeup scheduled at time T，表示当前Reactor正处于超时等待状态
     */
    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    /**
     * 非阻塞selectNow()检查当前是否有IO就绪事件发生
     */
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            // 非阻塞
            return selectNow();
        }
    };
    int selectNow() throws IOException {
        return selector.selectNow();
    }
    /**
     * Selector轮询策略：决定什么时候轮询，什么时候处理IO事件，什么时候执行异步任务
     */
    private final SelectStrategy selectStrategy;
    /**
     * 执行IO事件和执行异步任务的CPU时间比例：默认50，表示执行IO事件和异步任务的时间比例是1：1
     */
    private volatile int ioRatio = 50;
    /**
     * 记录Selector上移除SocketChannel的个数：达到256个 则需要将无效的selectKey从SelectedKeys集合中清除掉
     */
    private int cancelledKeys;
    /**
     * 用于从IO就绪的SelectedKeys集合中剔除已经失效的selectKey
     * 用于及时从SelectedKeys中清除失效的SelectKey，例如SocketChannel从Selector上被用户移除
     */
    private boolean needsToSelectAgain;

    /**
     * 构造方法
     *
     * @param queueFactory 任务队列工厂，用于创建队列来保存Reactor中待执行的异步任务
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 创建IO多路复用的Selector
        final SelectorTuple selectorTuple = openSelector();
        // 通过用SelectedSelectionKeySet装饰后的unwrappedSelector
        this.selector = selectorTuple.selector;
        // 优化后的JDK NIO 原生Selector
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    /**
     * 创建任务队列
     */
    private static Queue<Runnable> newTaskQueue(EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // 创建无界 或 有界任务队列
        // MpscQueue是由JCTools提供的一个高性能无锁队列，适用于多生产者单消费者的场景，它支持多个生产者线程安全的访问队列，
        // 同一时刻只允许一个消费者线程读取队列中的元素。
        return maxPendingTasks == Integer.MAX_VALUE
                ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    /**
     * SelectorTuple
     */
    private static final class SelectorTuple {

        /**
         * unwrappedSelector：Netty优化过的JDK NIO原生Selector
         */
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 创建IO多路复用的Selector，并对创建出来的JDK NIO 原生的Selector进行性能优化
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 通过JDK NIO SelectorProvider创建Selector
            // SelectorProvider会根据操作系统的不同选择JDK在不同操作系统版本下的对应Selector的实现。Linux下会选择Epoll，Mac下会选择Kqueue。
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 若Selector优化开关关闭，直接返回JDK NIO原生Selector，默认开启需要对Selector进行优化
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        // 判断由SelectorProvider创建出来的Selector是否是JDK NIO原生的Selector实现，若不是则直接返回
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });
        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        // 创建SelectedSelectionKeySet：其基于数组实现，可利用CPU缓存的优势来提高遍历的效率，通过反射替换掉sun.nio.ch.SelectorImpl类中selectedKeys和publicSelectedKeys的默认HashSet实现
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");
                    // Java9版本以上通过sun.misc.Unsafe设置字段值的方式
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    // 通过反射的方式用SelectedSelectionKeySet替换掉HashSet实现的electedKeys，sun.nio.ch.SelectorImpl#publicSelectedKeys。
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // Java8反射替换字段
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Reactor线程的核心工作：轮询所有注册其上的Channel中的IO就绪事件，处理对应Channel上的IO事件，执行异步任务。
     */
    @Override
    protected void run() {
        // 记录轮询次数：用于解决JDK epoll的空轮询bug
        int selectCnt = 0;
        for (; ; ) {
            try {

                // #1 根据轮询策略获取轮询结果
                int strategy;
                try {
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                        case SelectStrategy.CONTINUE:
                            continue;
                        case SelectStrategy.BUSY_WAIT:
                            // NIO不支持自旋（BUSY_WAIT）
                        case SelectStrategy.SELECT:
                            // strategy == -1：Reactor中没有异步任务需要执行，Reactor线程可以安心的阻塞在Selector上等待IO就绪事件发生。
                            long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                            if (curDeadlineNanos == -1L) {
                                // 若当前并没有定时任务需要执行，则设置为Long.MAX_VALUE一直阻塞，直到有IO就绪事件到达或者有异步任务需要执行
                                curDeadlineNanos = NONE;
                            }
                            // 最早执行定时任务的deadline作为select的阻塞时间，意思是到了定时任务的执行时间，不管有无IO就绪事件，必须唤醒selector，从而使Reactor线程执行定时任务
                            nextWakeupNanos.set(curDeadlineNanos);
                            try {
                                // 再次检查普通任务队列中是否有异步任务，若没有则开始select阻塞轮询IO就绪事件
                                if (!hasTasks()) {
                                    strategy = select(curDeadlineNanos);
                                }
                            } finally {
                                // 执行到这里说明Reactor已经从Selector上被唤醒了，设置Reactor的状态为苏醒状态AWAKE
                                // lazySet优化不必要的volatile操作，不使用内存屏障，不保证写操作的可见性（单线程不需要保证）
                                nextWakeupNanos.lazySet(AWAKE);
                            }
                        default:
                            // strategy >= 0：Reactor中没有IO就绪事件、但是有异步任务需要执行，流程通过default分支直接进入了处理异步任务的逻辑部分
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                //  #2 执行到这里说明满足了唤醒条件，Reactor线程从selector上被唤醒开始处理IO就绪事件和执行异步任务
                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;

                // 优先处理IO就绪事件，然后根据ioRatio设置的处理IO事件CPU用时与异步任务CPU用时比例，来决定执行多长时间的异步任务
                final int ioRatio = this.ioRatio;
                boolean ranTasks;
                if (ioRatio == 100) {
                    // #2.1 ioRatio = 100表示无需考虑执行时间的限制，当有IO就绪事件时，Reactor线程要优先处理IO就绪事件，最后再执行所有异步任务（普通任务、尾部任务、定时任务）
                    try {
                        if (strategy > 0) {
                            processSelectedKeys();
                        }
                    } finally {
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    // #2.2 先执行IO事件，用时ioTime，最后再执行异步任务只能用时ioTime * (100 - ioRatio) / ioRatio
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        final long ioTime = System.nanoTime() - ioStartTime;
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    // #2.3 没有IO就绪事件处理，则只执行异步任务 最多执行64个 防止Reactor线程处理异步任务时间过长而导致 I/O 事件阻塞
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                // #3 判断是否触发JDK Epoll BUG 触发空轮询
                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    selectCnt = 0;
                } else if (unexpectedSelectorWakeup(selectCnt)) {
                    // 既没有IO就绪事件，也没有异步任务，Reactor线程从Selector上被异常唤醒 触发JDK Epoll空轮训BUG
                    // 重新构建Selector、selectCnt归零
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw (Error) e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    if (isShuttingDown()) {
                        // 切走程序承担的现有流量：关闭Reactor上注册的所有Channel，停止处理IO事件，触发unActive以及unRegister事件
                        closeAll();
                        // 保证现有剩余的任务可以执行完毕，保证业务无损：注销掉所有Channel停止处理IO事件之后，剩下的就需要执行Reactor中剩余的异步任务了
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    throw (Error) e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * select等待IO就绪事件
     */
    private int select(long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) {
            // 若没有定时任务、无普通任务执行时，则开始轮询IO就绪事件，一直阻塞 直到唤醒条件成立
            return selector.select();
        }
        // 场景：当最近的一个定时任务的deadline即将在5微秒内到达，则将纳秒转换成毫秒计算出的timeoutMillis会是0，
        // 而timeoutMillis == 0表示定时任务执行时间已经到达deadline时间点，需要被执行，因此再加上0.995毫秒凑成1毫秒不能让其为0
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    /**
     * 唤醒Reactor线程
     */
    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // 将Reactor线程从Selector上唤醒
            selector.wakeup();
        }
    }

    /**
     * 处理IO就绪事件
     */
    private void processSelectedKeys() {
        // 是否采用netty优化后的selectedKey集合类型 是由变量DISABLE_KEY_SET_OPTIMIZATION决定的 默认为false
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    /**
     * 处理IO事件：采用优化过的Selector对IO就绪事件处理，默认是该流程
     */
    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            selectedKeys.keys[i] = null;
            final Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            if (needsToSelectAgain) {
                selectedKeys.reset(i + 1);
                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理IO事件：采用JDK 原生的Selector对IO就绪事件处理
     */
    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        if (selectedKeys.isEmpty()) {
            return;
        }
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (; ; ) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            // 注意：Selector不会自己从已选择键集中移除SelectionKey实例，必须在处理完通道时自己移除。下次该通道变成就绪时，Selector会再次将其放入已选择键集中。
            i.remove();
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            if (!i.hasNext()) {
                break;
            }
            // 为了保证Selector中所有KeySet的有效性，需要在Channel取消个数达到256时，触发一次selectNow，目的是清除无效的SelectionKey
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();
                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 处理IO事件
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // #1 若SelectionKey已经失效，则关闭对应的Channel
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                return;
            }
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }
        // #2 获取IO就绪事件，并处理
        try {
            // #2.1 处理Connect事件：Netty客户端向服务端发起连接，并向客户端的Reactor注册Connect事件，当连接建立成功后，客户端的NioSocketChannel就会产生Connect就绪事件
            int readyOps = k.readyOps();
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                int ops = k.interestOps();
                // 移除对Connect事件的监听，否则Selector会一直通知
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);
                // 触发channelActive事件处理Connect事件
                unsafe.finishConnect();
            }
            // #2.2 处理Write事件：OP_WRITE事件的注册是由用户来完成的，当Socket发送缓冲区已满无法继续写入数据时，用户会向Reactor注册OP_WRITE事件，
            // 等到Socket发送缓冲区变得可写时，Reactor会收到OP_WRITE事件活跃通知，随后在这里调用客户端NioSocketChannel中的forceFlush方法将剩余数据发送出去。
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                ch.unsafe().forceFlush();
            }
            // #2.3 处理Read事件 或 Accept事件
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * 将SocketChannel从Selector中移除 取消监听IO事件
     */
    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys++;
        // 当从selector中移除的socketChannel数量达到256个，设置needsToSelectAgain为true
        // 在io.netty.channel.nio.NioEventLoop.processSelectedKeysPlain 中重新做一次轮询，将失效的selectKey移除，以保证selectKeySet的有效性
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            // 会清除无效的SelectionKey
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }

    /**
     * returns true if selectCnt should be reset
     */
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        /*
         * 走到这里的条件是 既没有IO就绪事件，也没有异步任务，Reactor线程从Selector上被异常唤醒
         * 这种情况可能是已经触发了JDK Epoll的空轮询BUG，若持续512次 则认为可能已经触发BUG，于是重建Selector
         */
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 && selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    /**
     * 重建Selector
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    /**
     * 重建Selector
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;
        if (oldSelector == null) {
            return;
        }
        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }
        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key : oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 切走流量：关闭Reactor上注册的所有Channel，停止处理IO事件，触发unActive以及unRegister事件
     */
    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k : keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }
        // 关闭Reactor上注册的所有Channel，并在pipeline中触发unActive事件和unRegister事件
        for (AbstractNioChannel ch : channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }









    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }



    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }




    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }



    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
                case 0:
                    k.cancel();
                    invokeChannelUnregistered(task, k, null);
                    break;
                case 1:
                    if (!k.isValid()) { // Cancelled by channelReady()
                        invokeChannelUnregistered(task, k, null);
                    }
                    break;
                default:
                    break;
            }
        }
    }





    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

}
