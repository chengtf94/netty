/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import io.netty.util.Recycler;

/**
 * 轻量级对象池顶层抽象：定义了对象池的行为，以及各种顶层接口。
 */
public abstract class ObjectPool<T> {

    ObjectPool() {
    }

    /**
     * 从对象池获取对象
     */
    public abstract T get();

    /**
     * Handle是池化对象在对象池中的一个模型，里面包裹了池化对象，并包含了池化对象的一些回收信息，以及池化对象的回收状态。
     * 默认实现是DefaultHandle
     */
    public interface Handle<T> {
        /**
         * 将对象回收至对象池
         */
        void recycle(T self);
    }

    /**
     * 对象池创建对象的行为接口
     */
    public interface ObjectCreator<T> {
        /**
         * 创建对象
         */
        T newObject(Handle<T> handle);
    }

    /**
     * 创建对象池
     */
    public static <T> ObjectPool<T> newPool(final ObjectCreator<T> creator) {
        return new RecyclerObjectPool<T>(ObjectUtil.checkNotNull(creator, "creator"));
    }

    /**
     * Recycler对象池
     */
    private static final class RecyclerObjectPool<T> extends ObjectPool<T> {
        /**
         * recycler对象池实例：也就是真正的对象池
         */
        private final Recycler<T> recycler;

        /**
         * 构造方法
         */
        RecyclerObjectPool(final ObjectCreator<T> creator) {
            recycler = new Recycler<T>() {
                @Override
                protected T newObject(Handle<T> handle) {
                    return creator.newObject(handle);
                }
            };
        }

        @Override
        public T get() {
            return recycler.get();
        }
    }

}
