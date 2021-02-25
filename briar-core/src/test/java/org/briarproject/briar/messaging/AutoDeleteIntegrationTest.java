package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.MessageDeletedException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.system.TimeTravelModule;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.test.BriarIntegrationTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.briarproject.briar.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.sort;
import static org.briarproject.bramble.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.messaging.MessagingConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AutoDeleteIntegrationTest
		extends BriarIntegrationTest<BriarIntegrationTestComponent> {

	@Override
	protected void createComponents() {
		BriarIntegrationTestComponent component =
				DaggerBriarIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		c0 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t0Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		c1 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t1Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		c2 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t2Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);

		// Use different times to avoid creating identical messages that are
		// treated as redundant copies of the same message (#1907)
		try {
			long now = System.currentTimeMillis();
			c0.getTimeTravel().setCurrentTimeMillis(now);
			c1.getTimeTravel().setCurrentTimeMillis(now + 1);
			c2.getTimeTravel().setCurrentTimeMillis(now + 2);
		} catch (InterruptedException e) {
			fail();
		}
	}

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		// Run the initial cleanup task that was scheduled at startup
		c0.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c1.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c2.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
	}

	@Test
	public void testMessageWithoutTimer() throws Exception {
		// 0 creates a message without a timer
		MessageId messageId = createMessageWithoutTimer(c0, contactId1From0);
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		ConversationMessageHeader h0 = headers0.get(0);
		assertEquals(messageId, h0.getId());
		// The message should not have a timer
		assertEquals(NO_AUTO_DELETE_TIMER, h0.getAutoDeleteTimer());
		// Sync the message to 1
		sync0To1(1, true);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		ConversationMessageHeader h1 = headers1.get(0);
		assertEquals(messageId, h1.getId());
		// The message should not have a timer
		assertEquals(NO_AUTO_DELETE_TIMER, h1.getAutoDeleteTimer());
	}

	@Test
	public void testDefaultTimer() throws Exception {
		// 0 creates a message with the default timer
		MessageId messageId = createMessageWithTimer(c0, contactId1From0);
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		ConversationMessageHeader h0 = headers0.get(0);
		assertEquals(messageId, h0.getId());
		// The message should have the default timer (none)
		assertEquals(NO_AUTO_DELETE_TIMER, h0.getAutoDeleteTimer());
		// Sync the message to 1
		sync0To1(1, true);
		// Sync the ack to 0
		ack1To0(1);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		ConversationMessageHeader h1 = headers1.get(0);
		assertEquals(messageId, h1.getId());
		// The message should have the default timer (none)
		assertEquals(NO_AUTO_DELETE_TIMER, h1.getAutoDeleteTimer());
		// Both peers should still be using the default timer
		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));
	}

	@Test
	public void testNonDefaultTimer() throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 should be using the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		// 1 should still be using the default timer
		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));
		// 0 creates a message with the new timer
		MessageId messageId = createMessageWithTimer(c0, contactId1From0);
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());
		// Sync the message to 1
		sync0To1(1, true);
		// Sync the ack to 0 - this starts 0's timer
		ack1To0(1);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());
		// Both peers should be using the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));
		// Before 0's timer elapses, both peers should still see the message
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
	}

	@Test
	public void testTimerIsMirrored() throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 should be using the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		// 1 should still be using the default timer
		assertEquals(NO_AUTO_DELETE_TIMER,
				getAutoDeleteTimer(c1, contactId0From1));
		// 0 creates a message with the new timer
		MessageId messageId0 = createMessageWithTimer(c0, contactId1From0);
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId0, headers0.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());
		// Sync the message to 1
		sync0To1(1, true);
		// Sync the ack to 0 - this starts 0's timer
		ack1To0(1);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId0, headers1.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());
		// 0 and 1 should both be using the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));
		// Before 0's timer elapses, both peers should still see the message
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId0);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		// 1 creates a message
		MessageId messageId1 = createMessageWithTimer(c1, contactId0From1);
		// The message should have been added to 1's view of the conversation
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId1, headers1.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers1.get(0).getAutoDeleteTimer());
		// Sync the message to 0
		sync1To0(1, true);
		// Sync the ack to 1 - this starts 1's timer
		ack0To1(1);
		// The message should have been added to 0's view of the conversation
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId1, headers0.get(0).getId());
		// The message should have the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				headers0.get(0).getAutoDeleteTimer());
		// 0 and 1 should both be using the new timer
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c0, contactId1From0));
		assertEquals(MIN_AUTO_DELETE_TIMER_MS,
				getAutoDeleteTimer(c1, contactId0From1));
		// Before 1's timer elapses, both peers should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation but 0 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		// 0 marks the message as read - this starts 0's timer
		markMessageRead(c0, contact1From0, messageId1);
		// Before 0's timer elapses, 0 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
	}

	@Test
	public void testMessageWithAttachment() throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 creates an attachment
		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);
		// 0 creates a message with the new timer and the attachment
		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		// Sync the message and the attachment to 1
		sync0To1(2, true);
		// Sync the acks to 0 - this starts 0's timer
		ack1To0(2);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Before 0's timer elapses, both peers should still see the message
		// and both should have the attachment
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	@Test
	public void testPrivateMessageWithMissingAttachmentIsDeleted()
			throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 creates an attachment
		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);
		// 0 creates a message with the new timer and the attachment
		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		// Unshare the attachment so it won't be synced yet
		setMessageNotShared(c0, attachmentHeader.getMessageId());
		// Sync the message (but not the attachment) to 1
		sync0To1(1, true);
		// Sync the ack to 0 - this starts 0's timer
		ack1To0(1);
		// The message should have been added to 1's view of the conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertEquals(messageId, headers1.get(0).getId());
		// Before 0's timer elapses, both peers should still see the message
		// and 0 should still have the attachment (1 hasn't received it)
		long timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
	}

	@Test
	public void testOrphanedAttachmentIsDeleted() throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 creates an attachment
		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);
		// 0 creates a message with the new timer and the attachment
		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		// Unshare the private message so it won't be synced yet
		setMessageNotShared(c0, messageId);
		// Sync the attachment (but not the message) to 1 - this starts 1's
		// orphan cleanup timer
		sync0To1(1, true);
		// Sync the ack to 0
		ack1To0(1);
		// The message should not have been added to 1's view of the
		// conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Before 1's timer elapses, both peers should still have the attachment
		long timerLatency =
				MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 1's timer has elapsed, 1 should no longer have the attachment
		// but 0 should still have it
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Share the private message and sync it - too late to stop 1's orphan
		// cleanup timer
		setMessageShared(c0, messageId);
		sync0To1(1, true);
		// Sync the ack to 0 - this starts 0's timer
		ack1To0(1);
		// The message should have been added to 1's view of the conversation
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Before 0's timer elapses, both peers should still see the message
		// and 0 should still have the attachment (1 has deleted it)
		timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	@Test
	public void testOrphanedAttachmentIsNotDeletedIfPrivateMessageArrives()
			throws Exception {
		// Set 0's timer
		setAutoDeleteTimer(c0, contactId1From0, MIN_AUTO_DELETE_TIMER_MS);
		// 0 creates an attachment
		AttachmentHeader attachmentHeader =
				createAttachment(c0, contactId1From0);
		// 0 creates a message with the new timer and the attachment
		MessageId messageId = createMessageWithTimer(c0, contactId1From0,
				singletonList(attachmentHeader));
		// The message should have been added to 0's view of the conversation
		List<ConversationMessageHeader> headers0 =
				getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertEquals(messageId, headers0.get(0).getId());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		// Unshare the private message so it won't be synced yet
		setMessageNotShared(c0, messageId);
		// Sync the attachment (but not the message) to 1 - this starts 1's
		// orphan cleanup timer
		sync0To1(1, true);
		// Sync the ack to 0
		ack1To0(1);
		// The message should not have been added to 1's view of the
		// conversation
		List<ConversationMessageHeader> headers1 =
				getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		// Before 1's timer elapses, both peers should still have the attachment
		long timerLatency =
				MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Share the private message and sync it - just in time to stop 1's
		// orphan cleanup timer
		setMessageShared(c0, messageId);
		sync0To1(1, true);
		// The message should have been added to 1's view of the conversation
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 1's timer has elapsed, both peers should still see the message
		// and both should still have the attachment
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// Sync the ack to 0 - this starts 0's timer
		ack1To0(1);
		// Before 0's timer elapses, both peers should still see the message
		// and both should still have the attachment
		timerLatency = MIN_AUTO_DELETE_TIMER_MS + BATCH_DELAY_MS;
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(1, headers0.size());
		assertFalse(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 0's timer has elapsed, the message should be deleted from 0's
		// view of the conversation but 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// 1 marks the message as read - this starts 1's timer
		markMessageRead(c1, contact0From1, messageId);
		// Before 1's timer elapses, 1 should still see the message
		c0.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		c1.getTimeTravel().addCurrentTimeMillis(timerLatency - 1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(1, headers1.size());
		assertFalse(messageIsDeleted(c1, attachmentHeader.getMessageId()));
		// When 1's timer has elapsed, the message should be deleted from 1's
		// view of the conversation
		c0.getTimeTravel().addCurrentTimeMillis(1);
		c1.getTimeTravel().addCurrentTimeMillis(1);
		headers0 = getMessageHeaders(c0, contactId1From0);
		assertEquals(0, headers0.size());
		assertTrue(messageIsDeleted(c0, attachmentHeader.getMessageId()));
		headers1 = getMessageHeaders(c1, contactId0From1);
		assertEquals(0, headers1.size());
		assertTrue(messageIsDeleted(c1, attachmentHeader.getMessageId()));
	}

	private MessageId createMessageWithoutTimer(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationManager conversationManager =
				component.getConversationManager();
		MessagingManager messagingManager = component.getMessagingManager();
		PrivateMessageFactory factory = component.getPrivateMessageFactory();

		GroupId groupId = messagingManager.getConversationId(contactId);
		return db.transactionWithResult(false, txn -> {
			long timestamp = conversationManager
					.getTimestampForOutgoingMessage(txn, contactId);
			PrivateMessage m = factory.createPrivateMessage(groupId, timestamp,
					"Hi!", emptyList());
			messagingManager.addLocalMessage(txn, m);
			return m.getMessage().getId();
		});
	}

	private MessageId createMessageWithTimer(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		return createMessageWithTimer(component, contactId, emptyList());
	}

	private MessageId createMessageWithTimer(
			BriarIntegrationTestComponent component, ContactId contactId,
			List<AttachmentHeader> attachmentHeaders) throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		ConversationManager conversationManager =
				component.getConversationManager();
		AutoDeleteManager autoDeleteManager = component.getAutoDeleteManager();
		MessagingManager messagingManager = component.getMessagingManager();
		PrivateMessageFactory factory = component.getPrivateMessageFactory();

		GroupId groupId = messagingManager.getConversationId(contactId);
		return db.transactionWithResult(false, txn -> {
			long timestamp = conversationManager
					.getTimestampForOutgoingMessage(txn, contactId);
			long timer = autoDeleteManager
					.getAutoDeleteTimer(txn, contactId, timestamp);
			PrivateMessage m = factory.createPrivateMessage(groupId, timestamp,
					"Hi!", attachmentHeaders, timer);
			messagingManager.addLocalMessage(txn, m);
			return m.getMessage().getId();
		});
	}

	private AttachmentHeader createAttachment(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		MessagingManager messagingManager = component.getMessagingManager();

		GroupId groupId = messagingManager.getConversationId(contactId);
		InputStream in = new ByteArrayInputStream(getRandomBytes(1234));
		return messagingManager.addLocalAttachment(groupId,
				component.getClock().currentTimeMillis(), "image/jpeg", in);
	}

	private void setMessageNotShared(BriarIntegrationTestComponent component,
			MessageId messageId) throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();

		db.transaction(false, txn -> db.setMessageNotShared(txn, messageId));
	}

	private void setMessageShared(BriarIntegrationTestComponent component,
			MessageId messageId) throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();

		db.transaction(false, txn -> db.setMessageShared(txn, messageId));
	}

	private List<ConversationMessageHeader> getMessageHeaders(
			BriarIntegrationTestComponent component, ContactId contactId)
			throws Exception {
		DatabaseComponent db = component.getDatabaseComponent();
		MessagingManager messagingManager = component.getMessagingManager();

		return sortHeaders(db.transactionWithResult(true, txn ->
				messagingManager.getMessageHeaders(txn, contactId)));
	}

	private long getAutoDeleteTimer(BriarIntegrationTestComponent component,
			ContactId contactId) throws DbException {
		DatabaseComponent db = component.getDatabaseComponent();
		AutoDeleteManager autoDeleteManager = component.getAutoDeleteManager();

		return db.transactionWithResult(false,
				txn -> autoDeleteManager.getAutoDeleteTimer(txn, contactId));
	}

	private void markMessageRead(BriarIntegrationTestComponent component,
			Contact contact, MessageId messageId) throws DbException {
		MessagingManager messagingManager = component.getMessagingManager();

		GroupId groupId = messagingManager.getContactGroup(contact).getId();
		messagingManager.setReadFlag(groupId, messageId, true);
	}

	private boolean messageIsDeleted(BriarIntegrationTestComponent component,
			MessageId messageId) throws DbException {
		DatabaseComponent db = component.getDatabaseComponent();

		try {
			db.transaction(true, txn -> db.getMessage(txn, messageId));
			return false;
		} catch (MessageDeletedException e) {
			return true;
		}
	}

	@SuppressWarnings({"UseCompareMethod", "Java8ListSort"}) // Animal Sniffer
	private List<ConversationMessageHeader> sortHeaders(
			Collection<ConversationMessageHeader> in) {
		List<ConversationMessageHeader> out = new ArrayList<>(in);
		sort(out, (a, b) ->
				Long.valueOf(a.getTimestamp()).compareTo(b.getTimestamp()));
		return out;
	}
}
