package org.nustaq.kontraktor;

import org.nustaq.kontraktor.impl.DispatcherThread;
import org.nustaq.kontraktor.impl.ElasticScheduler;

import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * implementation of the Future interface.
 *
 * A Promise is unfulfilled or "unsettled" once it has not been set a result.
 * Its 'rejected' once an error has been set "reject(..)".
 * Its 'resolved' once a result has been set "resolve(..)".
 * Its 'settled' once a result or error has been set.
 */
public class Promise<T> implements Future<T> {
    protected Object result = null;
    protected Object error;
    protected Callback resultReceiver;
    // fixme: use bits
    protected volatile boolean hadResult;
    protected boolean hasFired;
    // probably unnecessary, increases cost
    // of allocation. However for now stay safe and optimize
    // from a proven-working implementation
    // note: if removed some field must set to volatile
    final AtomicBoolean lock = new AtomicBoolean(false); // (AtomicFieldUpdater is slower!)
    String id;
    Future nextFuture;

    /**
     * create a settled Promise by either providing an result or error.
     * @param result
     * @param error
     */
    public Promise(T result, Object error) {
        this.result = result;
        this.error = error;
        hadResult = true;
    }

    /**
     * create a resolved Promise by providing a result (cane be null).
     * @param error
     */
    public Promise(T result) {
        this(result,null);
    }

    /**
     * create an unfulfilled/unsettled Promise
     */
    public Promise() {}

    /**
     * remoting helper
     */
    public String getId() {
        return id;
    }

    /**
     * remoting helper
     */
    public Promise<T> setId(String id) {
        this.id = id;
        return this;
    }

    /**
     * see Future interface
     */
    @Override
    public Future<T> then(Runnable result) {
        return then( (r,e) -> result.run() );
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future<T> onResult(Consumer<T> resultHandler) {
        return then( (r,e) -> {
            if ( e == null ) {
                resultHandler.accept((T) r);
            }
        });
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future<T> onError(Consumer errorHandler) {
        return then( (r,e) -> {
            if ( e != null && e != Timeout.INSTANCE) {
                errorHandler.accept(e);
            }
        });
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future<T> onTimeout(Consumer timeoutHandler) {
        return then( (r,e) -> {
            if ( e == Timeout.INSTANCE ) {
                timeoutHandler.accept(e);
            }
        });
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public <OUT> Future<OUT> then(final Function<T, Future<OUT>> function) {
        Promise res = new Promise<>();
        then( new Callback<T>() {
            @Override
            public void settle(T result, Object error) {
                if ( Actor.isError(error) ) {
                    res.settle(null, error);
                } else {
                    function.apply(result).then(res);
                }
            }
        });
        return res;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public <OUT> Future<OUT> then(Consumer<T> function) {
        Promise res = new Promise<>();
        then( new Callback<T>() {
            @Override
            public void settle(T result, Object error) {
                if ( Actor.isError(error) ) {
                    res.settle(null, error);
                } else {
                    function.accept(result);
                    res.settle();
                }
            }
        });
        return res;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future<T> then(Supplier<Future<T>> callable) {
        Promise res = new Promise<>();
        then( new Callback<T>() {
            @Override
            public void settle(T result, Object error) {
                if ( Actor.isError(error) ) {
                    res.settle(null, error);
                } else {
                    Future<T> call = null;
                    call = callable.get().then(res);
                }
            }
        });
        return res;
    }


    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public <OUT> Future<OUT> catchError(final Function<Object, Future<OUT>> function) {
        Promise res = new Promise<>();
        then( new Callback<T>() {
            @Override
            public void settle(T result, Object error) {
                if ( ! Actor.isError(error) ) {
                    res.settle(null, error);
                } else {
                    function.apply(error).then(res);
                }
            }
        });
        return res;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public <OUT> Future<OUT> catchError(Consumer<Object> function) {
        Promise res = new Promise<>();
        then( new Callback<T>() {
            @Override
            public void settle(T result, Object error) {
                if ( ! Actor.isError(error) ) {
                    res.settle(null, error);
                } else {
                    function.accept(error);
                    res.settle();
                }
            }
        });
        return res;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    public void timedOut( Timeout to ) {
        if (!hadResult ) {
            settle(null, to);
        }
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future then(Callback resultCB) {
        // FIXME: this can be implemented more efficient
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (resultReceiver != null)
                throw new RuntimeException("Double register of future listener");
            resultReceiver = resultCB;
            if (hadResult) {
                hasFired = true;
                if (nextFuture == null) {
                    nextFuture = new Promise(result, error);
                    lock.set(false);
                    resultCB.settle(result, error);
                } else {
                    lock.set(false);
                    resultCB.settle(result, error);
                    nextFuture.settle(result, error);
                    return nextFuture;
                }
            }
            if (resultCB instanceof Future) {
                return (Future) resultCB;
            }
            lock.set(false);
            while( !lock.compareAndSet(false,true) ) {}
            if (nextFuture == null) {
                return nextFuture = new Promise();
            } else {
                return nextFuture;
            }
        } finally {
            lock.set(false);
        }
    }

    /**
     * special method for tricky things. Creates a nextFuture or returns it.
     * current
     * @return
     */
    public Promise getNext() {
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (nextFuture == null)
                return new Promise();
            else
                return (Promise) nextFuture;
        } finally {
            lock.set(false);
        }
    }

    /**
     * see Future (inheriting Callback) interface
     */
    public Promise getLast() {
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (nextFuture == null)
                return this;
            else
                return ((Promise)nextFuture).getLast();
        } finally {
            lock.set(false);
        }
    }

    /**
     * same as then, but avoid creation of new future
     * @param resultCB
     */
    public void finallyDo(Callback resultCB) {
        // FIXME: this can be implemented more efficient
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (resultReceiver != null)
                throw new RuntimeException("Double register of future listener");
            resultReceiver = resultCB;
            if (hadResult) {
                hasFired = true;
                lock.set(false);
                resultCB.settle(result, error);
            }
        } finally {
            lock.set(false);
        }
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public final void settle(Object res, Object error) {
        this.result = res;
        Object prevErr = this.error;
        this.error = error;
        while( !lock.compareAndSet(false,true) ) {}
        try {
            if (hadResult) {
                if ( prevErr instanceof Timeout ) {
                    this.error = prevErr;
                    lock.set(false);
                    return;
                }
                lock.set(false);
                throw new RuntimeException("Double result received on future " + prevErr );
            }
            hadResult = true;
            if (resultReceiver != null) {
                if (hasFired) {
                    lock.set(false);
                    throw new RuntimeException("Double fire on callback");
                }
                hasFired = true;
                lock.set(false);
                resultReceiver.settle(result, error);
                resultReceiver = null;
                while (!lock.compareAndSet(false, true)) {
                }
                if (nextFuture != null) {
                    lock.set(false);
                    nextFuture.settle(result, error);
                }
                return;
            }
        } finally {
            lock.set(false);
        }
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public T get() {
        return (T) result;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public T await() {
        awaitFuture();
        return awaitHelper();
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future<T> awaitFuture() {
        if ( Thread.currentThread() instanceof DispatcherThread ) {
            DispatcherThread dt = (DispatcherThread) Thread.currentThread();
            Scheduler scheduler = dt.getScheduler();
            int idleCount = 0;
            while( ! isSettled() ) {
                if ( ! dt.pollQs() ) {
                    idleCount++;
                    dt.__stack.add(this);
                    scheduler.pollDelay(idleCount);
                    dt.__stack.remove(dt.__stack.size()-1);
                } else {
                    idleCount = 0;
                }
            }
            return this;
        } else {
            // if outside of actor machinery, just block (warning actually polls)
            while( ! isSettled() ) {
                LockSupport.parkNanos(1000*500);
            }
            return this;
        }
    }

    private T awaitHelper() {
        if ( Actor.isError(getError()) ) {
            if ( getError() instanceof Throwable ) {
                Actors.<RuntimeException>throwException((Throwable) getError());
                return null; // never reached
            }
            else {
                if ( getError() == Timeout.INSTANCE ) {
                    throw new TimeoutException();
                }
                throw new AwaitException(getError());
            }
        } else {
            return get();
        }
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Future timeoutIn(long millis) {
        final Actor actor = Actor.sender.get();
        if ( actor != null )
            actor.delayed(millis, ()-> timedOut(Timeout.INSTANCE));
        else {
            ElasticScheduler.delayedCalls.schedule( new TimerTask() {
                @Override
                public void run() {
                    timedOut(Timeout.INSTANCE);
                }
            },millis);
        }
        return this;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public Object getError() {
        return error;
    }

    /**
     * see Future (inheriting Callback) interface
     */
    @Override
    public boolean isSettled() {
        return hadResult;
    }


    // debug
    public boolean _isHadResult() {
        return hadResult;
    }

    // debug
    public boolean _isHasFired() {
        return hasFired;
    }

    @Override
    public String toString() {
        return "Result{" +
            "result=" + result +
            ", error=" + error +
            '}';
    }
}
