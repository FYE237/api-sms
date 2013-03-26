package net.sf.briar.android.groups;

import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import net.sf.briar.R;
import net.sf.briar.android.AscendingHeaderComparator;
import net.sf.briar.android.BriarActivity;
import net.sf.briar.android.BriarService;
import net.sf.briar.android.BriarService.BriarServiceConnection;
import net.sf.briar.android.widgets.CommonLayoutParams;
import net.sf.briar.android.widgets.HorizontalBorder;
import net.sf.briar.api.android.DatabaseUiExecutor;
import net.sf.briar.api.db.DatabaseComponent;
import net.sf.briar.api.db.DbException;
import net.sf.briar.api.db.GroupMessageHeader;
import net.sf.briar.api.db.NoSuchSubscriptionException;
import net.sf.briar.api.db.event.DatabaseEvent;
import net.sf.briar.api.db.event.DatabaseListener;
import net.sf.briar.api.db.event.GroupMessageAddedEvent;
import net.sf.briar.api.db.event.MessageExpiredEvent;
import net.sf.briar.api.db.event.RatingChangedEvent;
import net.sf.briar.api.db.event.SubscriptionRemovedEvent;
import net.sf.briar.api.messaging.Author;
import net.sf.briar.api.messaging.GroupId;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.google.inject.Inject;

public class GroupActivity extends BriarActivity implements DatabaseListener,
OnClickListener, OnItemClickListener {

	private static final Logger LOG =
			Logger.getLogger(GroupActivity.class.getName());

	private final BriarServiceConnection serviceConnection =
			new BriarServiceConnection();

	private boolean restricted = false;
	private String groupName = null;
	private GroupAdapter adapter = null;
	private ListView list = null;

	// Fields that are accessed from DB threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject @DatabaseUiExecutor private volatile Executor dbUiExecutor;
	private volatile GroupId groupId = null;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(null);

		Intent i = getIntent();
		restricted = i.getBooleanExtra("net.sf.briar.RESTRICTED", false);
		byte[] id = i.getByteArrayExtra("net.sf.briar.GROUP_ID");
		if(id == null) throw new IllegalStateException();
		groupId = new GroupId(id);
		groupName = i.getStringExtra("net.sf.briar.GROUP_NAME");
		if(groupName == null) throw new IllegalStateException();
		setTitle(groupName);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(CommonLayoutParams.MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		adapter = new GroupAdapter(this);
		list = new ListView(this);
		// Give me all the width and all the unused height
		list.setLayoutParams(CommonLayoutParams.MATCH_WRAP_1);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		layout.addView(list);

		layout.addView(new HorizontalBorder(this));

		ImageButton composeButton = new ImageButton(this);
		composeButton.setBackgroundResource(0);
		composeButton.setImageResource(R.drawable.content_new_email);
		composeButton.setOnClickListener(this);
		layout.addView(composeButton);

		setContentView(layout);

		// Bind to the service so we can wait for the DB to be opened
		bindService(new Intent(BriarService.class.getName()),
				serviceConnection, 0);
	}

	@Override
	public void onResume() {
		super.onResume();
		db.addListener(this);
		loadHeaders();
	}

	private void loadHeaders() {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					// Wait for the service to be bound and started
					serviceConnection.waitForStartup();
					// Load the headers from the database
					long now = System.currentTimeMillis();
					Collection<GroupMessageHeader> headers =
							db.getMessageHeaders(groupId);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Load took " + duration + " ms");
					// Wait for the headers to be displayed in the UI
					CountDownLatch latch = new CountDownLatch(1);
					displayHeaders(latch, headers);
					latch.await();
				} catch(NoSuchSubscriptionException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Subscription removed");
					finishOnUiThread();
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while loading headers");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayHeaders(final CountDownLatch latch,
			final Collection<GroupMessageHeader> headers) {
		runOnUiThread(new Runnable() {
			public void run() {
				try {
					adapter.clear();
					for(GroupMessageHeader h : headers) adapter.add(h);
					adapter.sort(AscendingHeaderComparator.INSTANCE);
					selectFirstUnread();
				} finally {
					latch.countDown();
				}
			}
		});
	}

	private void selectFirstUnread() {
		int firstUnread = -1, count = adapter.getCount();
		for(int i = 0; i < count; i++) {
			if(!adapter.getItem(i).isRead()) {
				firstUnread = i;
				break;
			}
		}
		if(firstUnread == -1) list.setSelection(count - 1);
		else list.setSelection(firstUnread);
	}

	@Override
	public void onActivityResult(int request, int result, Intent data) {
		if(result == ReadGroupMessageActivity.RESULT_PREV) {
			int position = request - 1;
			if(position >= 0 && position < adapter.getCount())
				showMessage(position);
		} else if(result == ReadGroupMessageActivity.RESULT_NEXT) {
			int position = request + 1;
			if(position >= 0 && position < adapter.getCount())
				showMessage(position);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		db.removeListener(this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		unbindService(serviceConnection);
	}

	public void eventOccurred(DatabaseEvent e) {
		if(e instanceof GroupMessageAddedEvent) {
			GroupMessageAddedEvent g = (GroupMessageAddedEvent) e;
			if(g.getGroup().getId().equals(groupId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Message added, reloading");
				loadHeaders();
			}
		} else if(e instanceof MessageExpiredEvent) {
			if(LOG.isLoggable(INFO)) LOG.info("Message expired, reloading");
			loadHeaders(); // FIXME: Don't reload everything
		} else if(e instanceof RatingChangedEvent) {
			if(LOG.isLoggable(INFO)) LOG.info("Rating changed, reloading");
			loadHeaders();
		} else if(e instanceof SubscriptionRemovedEvent) {
			SubscriptionRemovedEvent s = (SubscriptionRemovedEvent) e;
			if(s.getGroup().getId().equals(groupId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Subscription removed");
				finishOnUiThread();
			}
		}
	}

	public void onClick(View view) {
		Intent i = new Intent(this, WriteGroupMessageActivity.class);
		i.putExtra("net.sf.briar.RESTRICTED", restricted);
		i.putExtra("net.sf.briar.GROUP_ID", groupId.getBytes());
		startActivity(i);
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		showMessage(position);
	}

	private void showMessage(int position) {
		GroupMessageHeader item = adapter.getItem(position);
		Intent i = new Intent(this, ReadGroupMessageActivity.class);
		i.putExtra("net.sf.briar.GROUP_ID", groupId.getBytes());
		i.putExtra("net.sf.briar.GROUP_NAME", groupName);
		i.putExtra("net.sf.briar.MESSAGE_ID", item.getId().getBytes());
		Author author = item.getAuthor();
		if(author != null) {
			i.putExtra("net.sf.briar.AUTHOR_ID", author.getId().getBytes());
			i.putExtra("net.sf.briar.AUTHOR_NAME", author.getName());
			i.putExtra("net.sf.briar.RATING", item.getRating().toString());
		}
		i.putExtra("net.sf.briar.CONTENT_TYPE", item.getContentType());
		i.putExtra("net.sf.briar.TIMESTAMP", item.getTimestamp());
		i.putExtra("net.sf.briar.FIRST", position == 0);
		i.putExtra("net.sf.briar.LAST", position == adapter.getCount() - 1);
		startActivityForResult(i, position);
	}
}