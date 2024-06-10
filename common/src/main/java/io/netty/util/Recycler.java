/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * 基于thread-local stack的轻量级对象池
 * 对象回收方式：
 * 1）由创建线程回收。池化对象在创建线程中被创建出来后，一直在创建线程中被处理，处理完毕后由创建线程直接进行回收。而为了避免对象池不可控制地迅速膨胀，
 * 所以需要对创建线程回收对象的频率进行限制。这个回收频率由参数 RATIO 控制，默认为8，可由JVM启动参数 -D io.netty.recycler.ratio 指定。表示创建线程只回收 1 / 8 的对象，也就是每创建 8 个对象最后只回收 1个对象。
 * 2）由回收线程回收：池化对象在创建线程中被创建出来，但是业务的相关处理是在回收线程中，业务处理完毕后由回收线程负责回收。
 * 对象回收有一个基本原则就是对象是谁创建的，就要回收到创建线程对应的Stack中。
 * 所以回收线程就需要将池化对象回收至其创建线程对应的Stack中的WeakOrderQueue链表中，并等待创建线程将WeakOrderQueue链表中的待回收对象转移至Stack中的数组栈中。
 * 同样，回收线程也需要控制回收频率，由参数 DELAYED_QUEUE_RATIO 进行控制，默认也是8，可由JVM启动参数 -D io.netty.recycler.delayedQueue.ratio 指定，表示回收线程每处理完 8 个对象才回收 1 个对象。
 *
 */
public abstract class Recycler<T> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    /**
     * 一个空的Handler：表示该对象不会被池化
     */
    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    /**
     * ID生成器：即用于产生池化对象中的回收ID，主要用于标识池化对象被哪个线程回收
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * 创建池化对象的线程ID：static final字段 也就意味着所有的创建线程OWN_THREAD_ID都是相同的
     * 这里主要用来区分创建线程与非创建线程。多个非创建线程拥有各自不同的ID
     * 这里的视角只是针对池化对象来说的：区分创建它的线程，与其他回收线程
     * 收线程的ID是由其对应的 WeakOrderQueue 节点来分配的，一个 WeakOrderQueue 实例对应一个回收线程ID。
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 对象池中每个线程对应的Stack中可以存储池化对象的默认初始最大个数：默认为4096个
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    /**
     * 对象池中每个线程对应的Stack中可以存储池化对象的默认最大个数：默认为4096个
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     * 初始容量：min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256) ，不超过256个
     */
    private static final int INITIAL_CAPACITY;
    /**
     * 计算回收线程可帮助回收的最大容量因子：默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    /**
     * 每个回收线程最多可以帮助多少个创建线程回收对象：默认为cpu核数 * 2，注意这里是站在回收线程的角度
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * 回收线程对应的WeakOrderQueue节点中的Link链表中的节点存储待回收对象的容量：默认为16
     */
    private static final int LINK_CAPACITY;
    /**
     * 创建线程回收对象时的回收比例：默认是8，表示只回收1/8的对象，也就是产生8个对象回收一个对象到对象池中
     */
    private static final int RATIO;
    /**
     * 回收线程回收对象时的回收比例：默认也是8，同样也是为了避免回收线程回收队列疯狂增长，回收比例也是1/8
     */
    private static final int DELAYED_QUEUE_RATIO;

    static {

        // 对象池中的容量控制
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }
        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;
        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        // 回收线程可回收对象的容量控制
        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));
        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));
        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // 对象回收频率控制
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    /**
     * 创建线程持有对象池的最大容量
     */
    private final int maxCapacityPerThread;
    /**
     * 所有回收线程可回收对象的总量(计算因子)
     */
    private final int maxSharedCapacityFactor;
    /**
     * 创建线程的回收比例
     */
    private final int interval;
    /**
     * 一个回收线程可帮助多少个创建线程回收对象
     */
    private final int maxDelayedQueuesPerThread;
    /**
     * 回收线程回收比例
     */
    private final int delayedQueueInterval;
    /**
     * threadLocal：为每个线程维护一个独立的Stack结构，从而达到多线程无锁化获取对象的目的。
     */
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    /**
     * 构造方法
     */
    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 创建被池化对象
     */
    protected abstract T newObject(Handle<T> handle);

    /**
     * 从对象池中获取对象
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        // 如果对象池容量为0，则立马新创建一个对象返回，但是该对象不会回收进对象池
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        // 获取当前线程 保存池化对象的stack
        Stack<T> stack = threadLocal.get();
        // 从stack中pop出对象
        DefaultHandle<T> handle = stack.pop();
        // 若当前线程的stack中没有池化对象，则则直接创建对象
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }


    /**
     * Handle：是池化对象在对象池中的模型，包装了一些池化对象的回收信息和回收状态
     */
    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    /**
     * DefaultHandle：默认Handle，里面包裹了池化对象以及池化对象在对象池中的一些相关信息，例如池化对象的相关回收信息和回收状态
     */
    private static final class DefaultHandle<T> implements Handle<T> {
        /**
         * 用于标识最近被哪个线程回收，被回收之前均是0
         */
        int lastRecycledId;
        /**
         * 用于标识最终被哪个线程回收，在没被回收前是0
         * lastRecycledId、recycleId主要是用来标记池化对象所处的回收阶段，以及在这些回收阶段具体被哪个线程进行回收。
         * 由回收线程帮助回收时：首先由回收线程将池化对象暂时存储在其创建线程对应Stack中的WeakOrderQueue链表中。此时并没有完成真正的对象回收。
         * recycleId = 0，lastRecycledId = 回收线程Id（WeakOrderQueue#id）。
         * 当创建线程将WeakOrderQueue链表中的待回收对象转移至Stack结构中的数组栈之后，这时池化对象才算真正完成了回收动作。
         * recycleId = lastRecycledId = 回收线程Id（WeakOrderQueue#id）。
         */
        int recycleId;
        /**
         * 是否已经被回收
         */
        boolean hasBeenRecycled;
        /**
         * 强引用关联创建handle的stack
         */
        Stack<?> stack;
        /**
         * 池化对象
         */
        Object value;

        /**
         * 构造方法
         */
        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            stack.push(this);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    /**
     * WeakOrderQueue链表：用来存储其他线程帮助本线程回收的对象（我们称之为待回收对象）。
     * 其中每一个节点对应一个其他线程，这个其他线程为本线程回收的对象存储在对应的WeakOrderQueue节点中。
     * 注意：
     * 1）WeakOrderQueue节点的插入都是在链表的头结点进行插入，也就是头插法。
     * 2）WeakOrderQueue本身就是一个弱引用，引用对应的回收线程
     */
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        /**
         * Link：用于真正存储待回收对象的结构，继承AtomicInteger，本身可以用来当做write index使用
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            /**
             * 数组用来存储待回收对象：容量为16
             */
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];
            /**
             * 创建线程在转移Link节点中的待回收对象时，通过这个readIndex来读取未被转移的对象。
             * 由于readIndex只会被创建线程使用，所以这里并不需要保证原子性和可见性。用一个普通的int变量存储就好
             */
            int readIndex;
            /**
             * 链表下一个节点
             */
            Link next;
        }

        /**
         * 内部Link链表的头结点
         */
        private static final class Head {
            /**
             * 所有回收线程能够帮助创建线程回收对象的总容量 reserveSpaceForLink方法中会多线程操作该字段
             * 用于指示当前回收线程是否继续为创建线程回收对象，所有回收线程都可以看到，这个值是所有回收线程共享的。
             * 以便可以保证所有回收线程回收的对象总量不能超过availableSharedCapacity
             *
             */
            private final AtomicInteger availableSharedCapacity;
            /**
             * Link链表的头结点
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * 回收head节点的所有空间，并从链表中删除head节点，head指针指向下一节点
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            /**
             * 所有回收线程都可以看到，这个值是所有回收线程共享的。以便可以保证所有回收线程回收的对象总量不能超过availableSharedCapacity
             */
            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * @param link  新的head节点，当前head指针指向的节点已经被回收完毕
             */
            void relink(Link link) {
                // 更新availableSharedCapacity，因为当前link节点中的待回收对象已经被转移完毕，所以需要增加availableSharedCapacity的值
                reclaimSpace(LINK_CAPACITY);
                // head指针指向新的头结点（第一个未被回收完毕的link节点）
                this.link = link;
            }

            /**
             * 创建新的Link节点
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            /**
             * 目的是为接下来要创建的link预留空间容量
             */
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }

        }

        /**
         * Link链表的头结点：head指针始终指向第一个未被转移完毕的Link节点
         */
        private final Head head;
        /**
         * 尾结点
         */
        private Link tail;
        /**
         * 站在stack的视角中，stack中包含一个weakOrderQueue的链表，每个回收线程为当前stack回收的对象存放在回收线程对应的weakOrderQueue中
         * 这样通过stack中的这个weakOrderQueue链表，就可以找到其他线程为该创建线程回收的对象
         */
        private WeakOrderQueue next;
        /**
         * 回收线程回收ID：每个weakOrderQueue分配一个，同一个stack下的一个回收线程对应一个weakOrderQueue节点
         */
        private final int id = ID_GENERATOR.getAndIncrement();
        /**
         * 回收线程回收比例：默认是8
         */
        private final int interval;
        /**
         * 回收线程回收计数 回收1/8的对象
         */
        private int handleRecycleCount;

        /**
         * 构造方法
         */
        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            // 持有当前回收线程的弱应用
            super(thread);
            // 创建尾结点
            tail = new Link();
            // 创建头结点：availableSharedCapacity = maxCapacity / maxSharedCapacityFactor
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        void add(DefaultHandle<?> handle) {
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            handleRecycleCount = 0;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        /**
         * 回收对象转移：将weakOrderQueue链表中当前节点中包含的待回收对象，转移到当前stack中
         */
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {

            // 头结点为null说明还没有待回收对象
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            // 如果头结点中的待回收对象已经被转移完毕，判断是否有后续Link节点
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                // 当前Head节点已经被转移完毕，head指针向后移动，head指针始终指向第一个未被转移完毕的Link节点
                head = head.next;
                this.head.relink(head);
            }

            // 根据本次转移对象容量评估是否应该对Stack进行扩容
            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;
            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            // 转移回收对象
            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    /**
     * Stack：包含一个用数组实现的栈结构，这个栈结构正是对象池中真正用于存储池化对象的地方，我们每次从对象池中获取对象都会从这个栈结构中弹出栈顶元素。
     * 同样我们每次将使用完的对象归还到对象池中也是将对象压入这个栈结构中。
     * 为了避免这种不必要的同步竞争，Netty也采用了类似TLAB分配内存的方式，每个线程拥有一个独立Stack，这样当多个线程并发从对象池中获取对象时，
     * 都是从自己线程中的Stack中获取，全程无锁化运行，大大提高了多线程从对象池中获取对象的效率。
     */
    private static final class Stack<T> {
        /**
         * 创建线程保存池化对象的stack结构所属对象池recycler实例
         */
        final Recycler<T> parent;
        /**
         * 用弱引用来关联当前stack对应的创建线程 因为用户可能在某个地方引用了defaultHandler -> stack -> thread，可能存在这个引用链
         * 当创建线程死掉之后 可能因为这个引用链的存在而导致thread无法被回收掉
         */
        final WeakReference<Thread> threadRef;
        /**
         * 所有回收线程能够帮助当前创建线程回收对象的总容量
         */
        final AtomicInteger availableSharedCapacity;
        /**
         * 当前Stack对应的创建线程作为其他创建线程的回收线程时可以帮助多少个线程回收其池化对象
         */
        private final int maxDelayedQueues;
        /**
         * 当前创建线程对应的stack结构中的最大容量：默认4096个对象
         */
        private final int maxCapacity;
        /**
         * 当前创建线程回收对象时的回收比例
         */
        private final int interval;
        /**
         * 当前创建线程作为其他线程的回收线程时回收其他线程的池化对象比例
         */
        private final int delayedQueueInterval;
        /**
         * 当前Stack中的数组栈：默认初始容量256，最大容量为4096
         */
        DefaultHandle<?>[] elements;
        /**
         * 数组栈 栈顶指针
         */
        int size;
        /**
         * 回收对象计数 与 interval配合 实现只回收一定比例的池化对象
         */
        private int handleRecycleCount;
        /**
         * Stack结构中的WeakOrderQueue链表：线程回收的设计，核心还是无锁化，避免多线程回收相互竞争
         */
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        /**
         * 构造方法
         */
        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }

        /**
         * 普通出栈操作，从栈顶弹出一个回收对象
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) {
                // 如果当前线程所属stack已经没有对象可用，则遍历stack中的weakOrderQueue链表（其他线程帮助回收的对象存放在这里）将这些待回收对象回收进stack
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                // 如果WeakOrderQueue链表中也没有待回收对象可转移，直接返回null，新创建一个对象
                if (size <= 0) {
                    return null;
                }
            }
            size --;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            this.size = size;
            if (ret.lastRecycledId != ret.recycleId) {
                // recycleId != lastRecycledId：表示当前池化对象处于半回收状态。
                // 池化对象已经被业务线程处理完毕，并被回收线程回收至对应的WeakOrderQueue节点中。并等待创建线程将其最终转移至Stack结构中的数组栈中。
                throw new IllegalStateException("recycled multiple times");
            }
            // 对象初次创建以及回收对象再次使用时，重置recycleId = lastRecycleId = 0
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        /**
         * 回收转移：当Stack结构中的数组栈为空时，创建线程会遍历WeakOrderQueue链表，从而将回收线程为其回收的对象从WeakOrderQueue节点中转移至数组栈中。
         */
        private boolean scavenge() {
            //从其他线程回收的weakOrderQueue里 转移 待回收对像 到当前线程的stack中
            if (scavengeSome()) {
                return true;
            }
            // 如果weakOrderQueue中没有待回收对象可转移，那么就重置stack中的cursor.prev，因为在扫描weakOrderQueue链表的过程中，cursor已经发生变化了
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 回收转移核心逻辑
         */
        private boolean scavengeSome() {

            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                // 将weakOrderQueue链表中当前节点中包含的待回收对象，转移到当前stack中，一次转移一个link
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                // 如果当前cursor节点没有待回收对象可转移，那么就继续遍历链表获取下一个weakOrderQueue节点
                WeakOrderQueue next = cursor.getNext();
                // 如果当前weakOrderQueue对应的回收线程已经挂掉了，则判断当前weakOrderQueue节点是否还有可回收对象
                if (cursor.get() == null) {
                    if (cursor.hasFinalData()) {
                        // 回收weakOrderQueue中最后一点可回收对象，因为对应的回收线程已经死掉了，这个weakOrderQueue不会再有任何对象了
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }
                    // 回收线程已死，对应的weaoOrderQueue节点中的最后一点待回收对象也已经回收完毕，就需要将当前节点从链表中删除。unlink当前cursor节点
                    // 这里需要注意的是，netty永远不会删除第一个节点，因为更新头结点是一个同步方法，避免更新头结点而导致的竞争开销
                    // prev == null 说明当前cursor节点是头结点。不用unlink，如果不是头结点 就将其从链表中删除，因为这个节点不会再有线程来收集池化对象了
                    if (prev != null) {
                        // 确保当前weakOrderQueue节点在被GC之前，我们已经回收掉它所有的占用空间
                        cursor.reclaimAllSpaceAndUnlink();
                        // 利用prev指针删除cursor节点
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 整个recycler对象池唯一的一个同步方法：同步块非常小，逻辑简单，执行迅速
         */
        synchronized void setHead(WeakOrderQueue queue) {
            // 始终在weakOrderQueue链表头结点插入新的节点
            queue.setNext(head);
            head = queue;
        }

        /**
         * 扩容
         */
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);
            // 扩容后的新容量为最接近指定容量expectedCapacity的最大2的次幂
            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }


        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

    }
}
