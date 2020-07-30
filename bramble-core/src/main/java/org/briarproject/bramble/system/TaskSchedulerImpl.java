package org.briarproject.bramble.system;

import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.system.TaskScheduler;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A {@link TaskScheduler} that delegates all calls to a
 * {@link ScheduledExecutorService}.
 */
@ThreadSafe
@NotNullByDefault
class TaskSchedulerImpl implements TaskScheduler {

	private final ScheduledExecutorService delegate;

	TaskSchedulerImpl(ScheduledExecutorService delegate) {
		this.delegate = delegate;
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable task, long delay,
			TimeUnit unit) {
		return delegate.schedule(task, delay, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long delay,
			long interval, TimeUnit unit) {
		return delegate.scheduleAtFixedRate(task, delay, interval, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay,
			long interval, TimeUnit unit) {
		return delegate.scheduleWithFixedDelay(task, delay, interval, unit);
	}
}
