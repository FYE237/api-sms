package org.briarproject.api.forum;

import org.briarproject.api.crypto.PrivateKey;
import org.briarproject.api.identity.Author;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.MessageId;

import java.io.IOException;
import java.security.GeneralSecurityException;

public interface ForumPostFactory {

	ForumPost createAnonymousPost(GroupId groupId, long timestamp,
			MessageId parent, String contentType, byte[] body)
			throws IOException, GeneralSecurityException;

	ForumPost createPseudonymousPost(GroupId groupId, long timestamp,
			MessageId parent, Author author, String contentType, byte[] body,
			PrivateKey privateKey) throws IOException,
			GeneralSecurityException;
}