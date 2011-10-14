package net.sf.briar.api.plugins;

import net.sf.briar.api.ContactId;
import net.sf.briar.api.transport.BatchTransportReader;
import net.sf.briar.api.transport.BatchTransportWriter;

/**
 * An interface for receiving readers and writers created by a batch-mode
 * transport plugin.
 */
public interface BatchPluginCallback extends PluginCallback {

	void readerCreated(BatchTransportReader r);

	void writerCreated(ContactId c, BatchTransportWriter w);
}
