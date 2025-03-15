package io.netty.util;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * HashedWheelTimer：基于哈希算法的单层时间轮定时器
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 */
public class HashedWheelTimer implements Timer {
    static final InternalLogger logger = InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    private static final int INSTANCE_COUNT_LIMIT = 64;
    private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    /**
     * bucket数组
     */
    private final HashedWheelBucket[] wheel;

    /**
     * 掩码：用于基于任务执行时间快速定位其所属的bucket索引
     */
    private final int mask;

    /**
     * tick滴答时间间隔周期：单位为纳秒，后台线程每隔tickDuration推动一次定时任务的执行
     */
    private final long tickDuration;

    /**
     * 工作执行器：用于调度执行定时任务
     */
    private final Worker worker = new Worker();

    /**
     * 后台工作线程：用于调度执行定时任务
     */
    private final Thread workerThread;

    /**
     * 资源泄露跟踪器
     */
    private final ResourceLeakTracker<HashedWheelTimer> leak;

    /**
     * 未执行的任务数量、任务数量上限
     */
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    private final long maxPendingTimeouts;

    /**
     * 工作状态机：初始化、已启动、已关闭
     */
    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down

    /**
     * 后台工作线程的启动时间戳：单位为纳秒
     */
    private volatile long startTime;

    /**
     * 后台工作线程的启动时间戳计数器：用于通知提交任务的业务线程在后台线程启动后继续提交任务
     */
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);

    /**
     * 未执行的任务队列：基于MSPC队列（多生产者-单消费者队列）
     */
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();

    /**
     * 已取消的任务队列：基于MSPC队列（多生产者-单消费者队列）
     */
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();

    /**
     * 构造方法
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * 构造方法
     *
     * @param threadFactory      线程工厂：用于创建执行定时任务的后台线程
     * @param tickDuration       tick滴答时间间隔周期，默认为100
     * @param unit               tick滴答时间间隔周期的单位，默认为ms
     * @param ticksPerWheel      时间轮大小，默认为512
     * @param leakDetection      是否开启资源泄露检测
     * @param maxPendingTimeouts 未执行的任务数量上限，若为0或负数表示无上限，默认无上限
     */
    public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit,
                            int ticksPerWheel, boolean leakDetection, long maxPendingTimeouts) {

        ObjectUtil.checkNotNull(threadFactory, "threadFactory");
        ObjectUtil.checkNotNull(unit, "unit");
        ObjectUtil.checkPositive(tickDuration, "tickDuration");
        ObjectUtil.checkPositive(ticksPerWheel, "ticksPerWheel");

        // #1 创建时间轮
        wheel = createWheel(ticksPerWheel);
        mask = wheel.length - 1;

        // #2 转换tick滴答周期为纳秒单位，并验证合法性、控制最低周期为1ms
        long duration = unit.toNanos(tickDuration);
        if (duration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        if (duration < MILLISECOND_NANOS) {
            logger.warn("Configured tickDuration {} smaller then {}, using 1ms.", tickDuration, MILLISECOND_NANOS);
            this.tickDuration = MILLISECOND_NANOS;
        } else {
            this.tickDuration = duration;
        }

        // #3 创建后台工作线程
        workerThread = threadFactory.newThread(worker);

        // #4 创建资源泄露跟踪器
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;

        // #5 设置最多允许的任务数量
        this.maxPendingTimeouts = maxPendingTimeouts;

        // #6 检测是否存在过多的单层时间轮定时器，最多允许64个
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT
                && WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }

    }

    /**
     * 创建时间轮
     */
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    /**
     * 修正时间轮大小为2的幂
     */
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * 警告存在过多的单层时间轮定时器
     */
    private static void reportTooManyInstances() {
        if (logger.isErrorEnabled()) {
            String resourceType = simpleClassName(HashedWheelTimer.class);
            logger.error("You are creating too many " + resourceType + " instances. " +
                    resourceType + " is a shared resource that must be reused across the JVM, " +
                    "so that only a few instances are created.");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    /**
     * 提交定时任务
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        ObjectUtil.checkNotNull(task, "task");
        ObjectUtil.checkNotNull(unit, "unit");

        // #1 检测未执行的任务数量是否打到上限
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        // #2 启动后台工作线程
        start();

        // #3 创建HashedWheelTimeout，并入队、等待工作线程到下一次tick滴答处理（会把该任务添加至bucket中）
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        timeouts.add(timeout);
        return timeout;
    }

    /**
     * 启动后台工作线程
     */
    public void start() {

        // #1 更新工作状态
        switch (WORKER_STATE_UPDATER.get(this)) {
            case WORKER_STATE_INIT:
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    workerThread.start();
                }
                break;
            case WORKER_STATE_STARTED:
                break;
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // #2 业务线程等待后台工作线程启动并初始化起始时间戳
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
            }
        }

    }

    /**
     * 关闭定时器
     */
    @Override
    public Set<Timeout> stop() {
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(HashedWheelTimer.class.getSimpleName() +
                    ".stop() cannot be called from " +
                    TimerTask.class.getSimpleName());
        }

        // #1 更新工作状态：workerState can be 0 or 2 at this moment - let it always be 2.
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }
            return Collections.emptySet();
        }

        // #2 中断后台工作线程
        try {
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                boolean closed = leak.close(this);
                assert closed;
            }
        }

        // #3 返回未处理的定时任务
        return worker.unprocessedTimeouts();
    }

    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    /**
     * 内部类1：工作执行器：用于调度执行定时任务
     */
    private final class Worker implements Runnable {

        /**
         * tick滴答计数器
         */
        private long tick;

        /**
         * 未处理的定时任务集合
         */
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        @Override
        public void run() {

            // #1 初始化启动时间戳：We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
            startTime = System.nanoTime();
            if (startTime == 0) {
                startTime = 1;
            }

            // #2 通知业务线程startTime已经初始化完成、继续提交任务
            startTimeInitialized.countDown();

            // #3 自旋执行：每个tick滴答间隔，将队列中的任务存入bucket、并执行当前tick滴答对应的bucket中的任务
            do {
                // #3.1 睡眠等待下一次tick滴答
                final long deadline = waitForNextTick();
                if (deadline > 0) {

                    // #3.2 处理已取消的任务
                    processCancelledTasks();

                    // #3.3 将队列中的任务存入bucket：每次最多转移存储10W个任务
                    transferTimeoutsToBuckets();

                    // #3.4 执行已过期的任务，也就是执行业务逻辑（注意：这里是单线程串行执行）
                    int idx = (int) (tick & mask);
                    HashedWheelBucket bucket = wheel[idx];
                    bucket.expireTimeouts(deadline);

                    tick++;
                }
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // #4 此时时间轮已关闭，则清除bucket、并添加到未处理的定时任务集合中（so we can return them from stop() method.）
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }

            // #4 将还在任务队列中未存入bucket的任务，添加到未处理的定时任务集合中
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }

            // #5 处理已取消的任务
            processCancelledTasks();
        }

        /**
         * 睡眠等待下一次tick滴答，并返回当前的时间戳
         */
        private long waitForNextTick() {
            long deadline = tickDuration * (tick + 1);
            for (; ; ) {

                // #1 计算睡眠等待的时间
                final long currentTime = System.nanoTime() - startTime;
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;
                if (sleepTimeMs <= 0) {
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // See https://github.com/netty/netty/issues/356
                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                    if (sleepTimeMs == 0) {
                        sleepTimeMs = 1;
                    }
                }

                // #2 睡眠
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }

            }
        }

        /**
         * 处理已取消的任务
         */
        private void processCancelledTasks() {
            for (; ; ) {
                HashedWheelTimeout timeout = cancelledTimeouts.poll();
                if (timeout == null) {
                    break;
                }
                try {
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * 将队列中的任务存入bucket：每次最多转移存储10W个任务
         */
        private void transferTimeoutsToBuckets() {
            for (int i = 0; i < 100000; i++) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    continue;
                }
                // #1 计算tick滴答数量、剩余轮数、任务对应的bucket索引
                long calculated = timeout.deadline / tickDuration;
                timeout.remainingRounds = (calculated - tick) / wheel.length;
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                int stopIndex = (int) (ticks & mask);
                // #2 存入bucket
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    /**
     * 内部类2：定时任务节点
     */
    private static final class HashedWheelTimeout implements Timeout {

        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        /**
         * 其所属的时间轮定时器
         */
        private final HashedWheelTimer timer;

        /**
         * 其绑定的定时任务
         */
        private final TimerTask task;

        /**
         * 其绑定的定时任务执行时间戳：相对于startTime
         */
        private final long deadline;

        /**
         * 其所属的bucket
         */
        HashedWheelBucket bucket;

        /**
         * 状态机：初始化、已取消、已执行
         */
        private static final int ST_INIT = 0;
        private static final int ST_CANCELLED = 1;
        private static final int ST_EXPIRED = 2;
        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        /**
         * 剩余轮数：由于是单层时间轮，因此引入轮数，用于支持时间比较长的任务，例如超过 tick滴答周期 * bucket大小的任务
         */
        long remainingRounds;

        /**
         * 前置节点、后置节点：组成双向链表
         */
        HashedWheelTimeout next;
        HashedWheelTimeout prev;

        /**
         * 构造方法
         */
        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        /**
         * 执行业务逻辑
         */
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }
            try {
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        /**
         * 移除任务
         */
        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                bucket.remove(this);
            } else {
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        @Override
        public boolean cancel() {
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            timer.cancelledTimeouts.add(this);
            return true;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        public int state() {
            return state;
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName(this))
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * 内部类3：基于哈希算法的时间轮bucket：每个元素存储由定时任务节点（HashedWheelTimeout）组成的双向链表
     */
    private static final class HashedWheelBucket {

        /**
         * 定时任务头节点、尾节点
         */
        private HashedWheelTimeout head;
        private HashedWheelTimeout tail;

        /**
         * 执行已过期的任务，也就是执行业务逻辑（注意：这里是单线程串行执行）
         */
        public void expireTimeouts(long deadline) {
            HashedWheelTimeout timeout = head;
            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                if (timeout.remainingRounds <= 0) {
                    // #1 若剩余轮数 <= 0，则移除该任务节点、并执行业务逻辑
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        timeout.expire();
                    } else {
                        throw new IllegalStateException(String.format("timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    // #2 若剩余轮数 > 0 而且 任务已取消，则移除该任务节点
                    next = remove(timeout);
                } else {
                    // #3 剩余轮数 > 0 而且 任务未取消，轮数减1
                    timeout.remainingRounds--;
                }
                timeout = next;
            }
        }

        /**
         * 添加任务节点
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * 移除任务节点
         */
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * 清除bucket、并添加未处理的定时任务
         */
        public void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }

    }

}
