package org.briarproject.plugins.file;

import org.briarproject.api.ContactId;
import org.briarproject.api.plugins.TransportConnectionReader;
import org.briarproject.api.plugins.TransportConnectionWriter;
import org.briarproject.api.plugins.simplex.SimplexPlugin;
import org.briarproject.api.plugins.simplex.SimplexPluginCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static org.briarproject.api.transport.TransportConstants.MIN_STREAM_LENGTH;

public abstract class FilePlugin implements SimplexPlugin {

	private static final Logger LOG =
			Logger.getLogger(FilePlugin.class.getName());

	protected final Executor ioExecutor;
	protected final SimplexPluginCallback callback;
	protected final int maxLatency;

	protected volatile boolean running = false;

	protected abstract File chooseOutputDirectory();
	protected abstract Collection<File> findFilesByName(String filename);
	protected abstract void writerFinished(File f);
	protected abstract void readerFinished(File f);

	protected FilePlugin(Executor ioExecutor, SimplexPluginCallback callback,
			int maxLatency) {
		this.ioExecutor = ioExecutor;
		this.callback = callback;
		this.maxLatency = maxLatency;
	}

	public int getMaxLatency() {
		return maxLatency;
	}

	public int getMaxIdleTime() {
		return Integer.MAX_VALUE; // We don't need keepalives
	}

	public boolean isRunning() {
		return running;
	}

	public TransportConnectionReader createReader(ContactId c) {
		return null;
	}

	public TransportConnectionWriter createWriter(ContactId c) {
		if (!running) return null;
		return createWriter(createConnectionFilename());
	}

	private String createConnectionFilename() {
		StringBuilder s = new StringBuilder(12);
		for (int i = 0; i < 8; i++) s.append((char) ('a' + Math.random() * 26));
		s.append(".dat");
		return s.toString();
	}

	// Package access for testing
	boolean isPossibleConnectionFilename(String filename) {
		return filename.toLowerCase(Locale.US).matches("[a-z]{8}\\.dat");
	}

	private TransportConnectionWriter createWriter(String filename) {
		if (!running) return null;
		File dir = chooseOutputDirectory();
		if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
		File f = new File(dir, filename);
		try {
			long capacity = dir.getFreeSpace();
			if (capacity < MIN_STREAM_LENGTH) return null;
			OutputStream out = new FileOutputStream(f);
			return new FileTransportWriter(f, out, capacity, this);
		} catch (IOException e) {
			if (LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			f.delete();
			return null;
		}
	}

	protected void createReaderFromFile(final File f) {
		if (!running) return;
		ioExecutor.execute(new ReaderCreator(f));
	}

	private class ReaderCreator implements Runnable {

		private final File file;

		private ReaderCreator(File file) {
			this.file = file;
		}

		public void run() {
			if (isPossibleConnectionFilename(file.getName())) {
				try {
					FileInputStream in = new FileInputStream(file);
					callback.readerCreated(new FileTransportReader(file, in,
							FilePlugin.this));
				} catch (IOException e) {
					if (LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				}
			}
		}
	}
}
