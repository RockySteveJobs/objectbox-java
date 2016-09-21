package io.objectbox;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A box to store objects of a particular class.
 * <p/>
 * Thread-safe.
 */
public class Box<T> {
    private final BoxStore store;
    private final Class<T> entityClass;

    /** Set when running inside TX */
    final ThreadLocal<Cursor<T>> txCursor = new ThreadLocal<>();
    private final ThreadLocal<Cursor<T>> threadLocalReader = new ThreadLocal<>();
    private final List<WeakReference<Cursor<T>>> readers = new ArrayList<>();

    Box(BoxStore store, Class<T> entityClass) {
        this.store = store;
        this.entityClass = entityClass;
    }

    private Cursor<T> getReader() {
        Cursor<T> cursor = getTxCursor();
        if (cursor != null) {
            return cursor;
        } else {
            cursor = threadLocalReader.get();
            if (cursor != null) {
                if (cursor.isObsolete()) {
                    Transaction tx = cursor.getTx();
                    cursor.close();
                    tx.reset();
                    cursor = tx.createCursor(entityClass);
                    synchronized (readers) {
                        readers.add(new WeakReference<Cursor<T>>(cursor));
                    }
                    threadLocalReader.set(cursor);
                }
            } else {
                Transaction tx = store.beginReadTx();
                cursor = tx.createCursor(entityClass);
                synchronized (readers) {
                    readers.add(new WeakReference<Cursor<T>>(cursor));
                }
                threadLocalReader.set(cursor);
            }
        }
        return cursor;
    }

    private Cursor<T> getTxCursor() {
        Transaction activeTx = store.activeTx.get();
        if (activeTx != null) {
            if(activeTx.isClosed()) {
                throw new IllegalStateException("Active TX is closed");
            }
            Cursor cursor = txCursor.get();
            if (cursor == null || cursor.getTx().isClosed()) {
                cursor = activeTx.createCursor(entityClass);
                txCursor.set(cursor);
            }
            return cursor;
        }
        return null;
    }

    private Cursor<T> getWriter() {
        Cursor cursor = getTxCursor();
        if (cursor != null) {
            return cursor;
        } else {
            Transaction tx = store.beginTx();
            try {
                return tx.createCursor(entityClass);
            } catch (RuntimeException e) {
                tx.close();
                throw e;
            }
        }
    }

    private void commitWriter(Cursor<T> cursor) {
        // NOP if TX is ongoing
        if (txCursor.get() == null) {
            cursor.close();
            cursor.getTx().commitAndClose();
        }
    }

    private void releaseWriter(Cursor<T> cursor) {
        // NOP if TX is ongoing
        if (txCursor.get() == null) {
            Transaction tx = cursor.getTx();
            if (!tx.isClosed()) {
                cursor.close();
                tx.abort();
                tx.close();
            }
        }
    }

    void txCommitted(Transaction tx) {
        // TODO Unused readers should be disposed when a new write tx is committed
        // (readers hold on to old data pages and prevent to reuse them)

        // At least we should be able to clear the reader of the current thread if exists
        Cursor cursor = threadLocalReader.get();
        if (cursor != null) {
            threadLocalReader.remove();
            Transaction cursorTx = cursor.getTx();
            cursor.close();
            cursorTx.close();
        }

        cursor = txCursor.get();
        if (cursor != null) {
            txCursor.remove();
            cursor.close();
        }
    }

    public T get(long key) {
        return getReader().get(key);
    }

    public long count() {
        return getReader().count();
    }

    public List<T> getAll() {
        Cursor<T> cursor = getReader();
        T first = cursor.first();
        if (first == null) {
            return Collections.emptyList();
        } else {
            ArrayList<T> list = new ArrayList<>();
            list.add(first);
            while (true) {
                T next = cursor.next();
                if (next != null) {
                    list.add(next);
                } else {
                    break;
                }
            }
            return list;
        }
    }

    public long put(T entity) {
        Cursor<T> cursor = getWriter();
        try {
            long key = cursor.put(entity);
            commitWriter(cursor);
            return key;
        } finally {
            releaseWriter(cursor);
        }
    }

    public void put(T... entities) {
        Cursor<T> cursor = getWriter();
        try {
            for (T entity : entities) {
                cursor.put(entity);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void put(Collection<T> entities) {
        Cursor<T> cursor = getWriter();
        try {
            for (T entity : entities) {
                cursor.put(entity);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void remove(long key) {
        Cursor<T> cursor = getWriter();
        try {
            cursor.deleteEntity(key);
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void remove(long... keys) {
        Cursor<T> cursor = getWriter();
        try {
            for (long key : keys) {
                cursor.deleteEntity(key);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void removeByKeys(Collection<Long> keys) {
        Cursor<T> cursor = getWriter();
        try {
            for (long key : keys) {
                cursor.deleteEntity(key);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void remove(T entity) {
        Cursor<T> cursor = getWriter();
        try {
            long key = cursor.getId(entity);
            cursor.deleteEntity(key);
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void remove(T... entities) {
        Cursor<T> cursor = getWriter();
        try {
            for (T entity : entities) {
                long key = cursor.getId(entity);
                cursor.deleteEntity(key);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void remove(Collection<T> entities) {
        Cursor<T> cursor = getWriter();
        try {
            for (T entity : entities) {
                long key = cursor.getId(entity);
                cursor.deleteEntity(key);
            }
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public void removeAll() {
        Cursor<T> cursor = getWriter();
        try {
            cursor.deleteAll();
            commitWriter(cursor);
        } finally {
            releaseWriter(cursor);
        }
    }

    public BoxStore getStore() {
        return store;
    }
}