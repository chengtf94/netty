package io.netty.channel.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * SelectedSelectionKeySet：基于数组自定义集合，为了优化对sun.nio.ch.SelectorImpl#selectedKeys集合的插入、遍历性能
 * 1）HashSet底层数据结构是一个哈希表，由于Hash冲突这种情况的存在，所以导致对哈希表进行插入和遍历操作的性能不如对数组进行插入和遍历操作的性能好
 * 2）数组可以利用CPU缓存的优势来提高遍历的效率
 */
final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    /**
     * SelectionKey数组、尾指针
     */
    SelectionKey[] keys;
    int size;

    SelectedSelectionKeySet() {
        keys = new SelectionKey[1024];
    }

    @Override
    public boolean add(SelectionKey o) {
        if (o == null) {
            return false;
        }
        keys[size++] = o;
        if (size == keys.length) {
            increaseCapacity();
        }
        return true;
    }

    /**
     * 扩容
     */
    private void increaseCapacity() {
        SelectionKey[] newKeys = new SelectionKey[keys.length << 1];
        System.arraycopy(keys, 0, newKeys, 0, size);
        keys = newKeys;
    }

    void reset() {
        reset(0);
    }

    void reset(int start) {
        Arrays.fill(keys, start, size, null);
        size = 0;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        return new Iterator<SelectionKey>() {
            private int idx;

            @Override
            public boolean hasNext() {
                return idx < size;
            }

            @Override
            public SelectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return keys[idx++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

}
