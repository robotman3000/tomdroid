/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 *
 * Copyright 2017, Harald Weiner (harald.weiner@jku.at)
 *
 * This file is part of Tomdroid.
 *
 * Tomdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tomdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Tomdroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tomdroid.sync.webdav;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tomdroid.Note;
import org.tomdroid.NoteManager;
import org.tomdroid.R;
import org.tomdroid.sync.ServiceAuth;
import org.tomdroid.sync.SyncService;
import org.tomdroid.util.ErrorList;
import org.tomdroid.util.Preferences;
import org.tomdroid.util.TLog;
import org.tomdroid.util.Time;
import org.tomdroid.xml.XmlUtils;

import android.app.Activity;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;

public class WebDAVSyncService extends SyncService implements ServiceAuth {

	private static final String TAG = "WebDAVSyncService";
	private String lastGUID;
	private long latestRemoteRevision = -1;
	private long latestLocalRevision = -1;

	public WebDAVSyncService(final Activity activity, final Handler handler) {
		super(activity, handler);
	}

	@Override
	public int getDescriptionAsId() {
		return R.string.prefTomboyWebDav;
	}

	@Override
	public String getName() {
		return "tomboy-webdav";
	}

	@Override
	public boolean isConfigured() {
		final OAuthConnection auth = this.getAuthConnection();
		return auth.isAuthenticated();
	}

	@Override
	public boolean needsServer() {
		return true;
	}

	@Override
	public boolean needsLocation() {
		return false;
	}

	@Override
	public boolean needsAuth() {
		final OAuthConnection auth = this.getAuthConnection();
		return !auth.isAuthenticated();
	}

	@Override
	public void getAuthUri(final String server, final Handler handler) {

		this.execInThread(new Runnable() {

			@Override
			public void run() {

				// Reset the authentication credentials
				final OAuthConnection auth = new OAuthConnection();
				Uri authUri = null;

				try {
					authUri = auth.getAuthorizationUrl(server);

				} catch (final UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
				}

				final Message message = new Message();
				message.obj = authUri;
				handler.sendMessage(message);
			}

		});
	}

	@Override
	public void remoteAuthComplete(final Uri uri, final Handler handler) {

		this.execInThread(new Runnable() {

			@Override
			public void run() {

				try {
					// TODO: might be intelligent to show something like a
					// progress dialog
					// else the user might try to sync before the authorization
					// process
					// is complete
					final OAuthConnection auth = WebDAVSyncService.this
							.getAuthConnection();
					final boolean result = auth.getAccess(uri
							.getQueryParameter("oauth_verifier"));

					if (result) {
						TLog.i(WebDAVSyncService.TAG,
								"The authorization process is complete.");
						handler.sendEmptyMessage(SyncService.AUTH_COMPLETE);
						return;
						// sync(true);
					} else {
						TLog.e(WebDAVSyncService.TAG,
								"Something went wrong during the authorization process.");
						WebDAVSyncService.this
						.sendMessage(SyncService.AUTH_FAILED);
					}
				} catch (final UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
				}

				// We don't care what we send, just remove the dialog
				handler.sendEmptyMessage(0);
			}
		});
	}

	@Override
	public boolean isSyncable() {
		return super.isSyncable() && this.isConfigured();
	}

	@Override
	protected void getNotesForSync(final boolean push) {
		SyncService.push = push;
		
		// start loading snowy notes
		this.setSyncProgress(0);
		this.lastGUID = null;

		TLog.v(WebDAVSyncService.TAG, "Loading Snowy notes");

		final String userRef = Preferences
				.getString(Preferences.Key.SYNC_SERVER_USER_API);

		this.syncInThread(new Runnable() {

			@Override
			public void run() {

				final OAuthConnection auth = WebDAVSyncService.this
						.getAuthConnection();
				WebDAVSyncService.this.latestRemoteRevision = (int) Preferences
						.getLong(Preferences.Key.LATEST_SYNC_REVISION);

				try {
					TLog.v(WebDAVSyncService.TAG, "contacting " + userRef);
					String rawResponse = auth.get(userRef);
					if (WebDAVSyncService.this.cancelled) {
						WebDAVSyncService.this.doCancel();
						return;
					}
					if (rawResponse == null) {
						WebDAVSyncService.this
						.sendMessage(SyncService.CONNECTING_FAILED);
						WebDAVSyncService.this.setSyncProgress(100);
						return;
					}

					WebDAVSyncService.this.setSyncProgress(30);

					try {
						JSONObject response = new JSONObject(rawResponse);

						// get notes list without content, to check for revision
						
						final String notesUrl = response.getJSONObject(
								"notes-ref").getString("api-ref");
						rawResponse = auth.get(notesUrl);
						response = new JSONObject(rawResponse);
						
						WebDAVSyncService.this.latestLocalRevision = Preferences
								.getLong(Preferences.Key.LATEST_SYNC_REVISION);
						
						WebDAVSyncService.this.setSyncProgress(35);

						WebDAVSyncService.this.latestRemoteRevision = response
								.getLong("latest-sync-revision");
						WebDAVSyncService.this
						.sendMessage(
								SyncService.LATEST_REVISION,
								(int) WebDAVSyncService.this.latestRemoteRevision,
								0);
						TLog.d(WebDAVSyncService.TAG,
								"old latest sync revision: {0}, remote latest sync revision: {1}",
								WebDAVSyncService.this.latestLocalRevision,
								WebDAVSyncService.this.latestRemoteRevision);

						final Cursor newLocalNotes = NoteManager
								.getNewNotes(WebDAVSyncService.this.activity);

						// same sync revision + no new local notes = no need to
						// sync
						
						if ((WebDAVSyncService.this.latestRemoteRevision <= WebDAVSyncService.this.latestLocalRevision)
								&& (newLocalNotes.getCount() == 0)) {
							TLog.v(WebDAVSyncService.TAG,
									"old sync revision on server, cancelling");
							WebDAVSyncService.this.finishSync(true);
							return;
						}

						// don't get notes if older revision - only pushing
						// notes
						
						if (push
								&& (WebDAVSyncService.this.latestRemoteRevision <= WebDAVSyncService.this.latestLocalRevision)) {
							TLog.v(WebDAVSyncService.TAG,
									"old sync revision on server, pushing new notes");
							
							final JSONArray notes = response
									.getJSONArray("notes");
							final List<String> notesList = new ArrayList<String>();
							for (int i = 0; i < notes.length(); i++) {
								notesList.add(notes.getJSONObject(i).optString(
										"guid"));
							}
							WebDAVSyncService.this
							.prepareSyncableNotes(newLocalNotes);
							WebDAVSyncService.this.setSyncProgress(50);
							return;
						}
						
						// get notes list with content to find changes
						
						TLog.v(WebDAVSyncService.TAG, "contacting " + notesUrl);
						WebDAVSyncService.this
						.sendMessage(SyncService.SYNC_CONNECTED);
						rawResponse = auth
								.get(notesUrl + "?include_notes=true");
						if (WebDAVSyncService.this.cancelled) {
							WebDAVSyncService.this.doCancel();
							return;
						}
						response = new JSONObject(rawResponse);
						WebDAVSyncService.this.latestRemoteRevision = response
								.getLong("latest-sync-revision");
						WebDAVSyncService.this
						.sendMessage(
								SyncService.LATEST_REVISION,
								(int) WebDAVSyncService.this.latestRemoteRevision,
								0);

						final JSONArray notes = response.getJSONArray("notes");
						WebDAVSyncService.this.setSyncProgress(50);

						TLog.v(WebDAVSyncService.TAG, "number of notes: {0}",
								notes.length());

						final ArrayList<Note> notesList = new ArrayList<Note>();

						for (int i = 0; i < notes.length(); i++) {
							notesList.add(new Note(notes.getJSONObject(i)));
						}

						if (WebDAVSyncService.this.cancelled) {
							WebDAVSyncService.this.doCancel();
							return;
						}

						// close cursor
						newLocalNotes.close();
						WebDAVSyncService.this.prepareSyncableNotes(notesList);
						
					} catch (final JSONException e) {
						TLog.e(WebDAVSyncService.TAG, e,
								"Problem parsing the server response");
						WebDAVSyncService.this.sendMessage(
								SyncService.PARSING_FAILED, ErrorList
								.createErrorWithContents(
										"JSON parsing", "json", e,
										rawResponse));
						WebDAVSyncService.this.setSyncProgress(100);
						return;
					}
				} catch (final java.net.UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
					WebDAVSyncService.this.setSyncProgress(100);
					return;
				}
				if (WebDAVSyncService.this.cancelled) {
					WebDAVSyncService.this.doCancel();
					return;
				}
				if (WebDAVSyncService.this.isSyncable()) {
					WebDAVSyncService.this.finishSync(true);
				}
			}
		});
	}

	@Override
	public void finishSync(final boolean refresh) {

		// delete leftover local notes
		NoteManager.purgeDeletedNotes(this.activity);
		
		final Time now = new Time();
		now.setToNow();
		final String nowString = now.formatTomboy();
		Preferences.putString(Preferences.Key.LATEST_SYNC_DATE, nowString);
		Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION,
				this.latestRemoteRevision);

		this.setSyncProgress(100);
		if (refresh) {
			this.sendMessage(SyncService.PARSING_COMPLETE);
		}
	}

	private OAuthConnection getAuthConnection() {

		// TODO: there needs to be a way to reset these values, otherwise cannot
		// change server!

		final OAuthConnection auth = new OAuthConnection();

		auth.accessToken = Preferences.getString(Preferences.Key.ACCESS_TOKEN);
		auth.accessTokenSecret = Preferences
				.getString(Preferences.Key.ACCESS_TOKEN_SECRET);
		auth.requestToken = Preferences
				.getString(Preferences.Key.REQUEST_TOKEN);
		auth.requestTokenSecret = Preferences
				.getString(Preferences.Key.REQUEST_TOKEN_SECRET);
		auth.oauth10a = Preferences.getBoolean(Preferences.Key.OAUTH_10A);
		auth.authorizeUrl = Preferences
				.getString(Preferences.Key.AUTHORIZE_URL);
		auth.accessTokenUrl = Preferences
				.getString(Preferences.Key.ACCESS_TOKEN_URL);
		auth.requestTokenUrl = Preferences
				.getString(Preferences.Key.REQUEST_TOKEN_URL);
		auth.rootApi = Preferences
				.getString(Preferences.Key.SYNC_SERVER_ROOT_API);
		auth.userApi = Preferences
				.getString(Preferences.Key.SYNC_SERVER_USER_API);

		return auth;
	}

	// push syncable notes
	@Override
	public void pushNotes(final ArrayList<Note> notes) {
		if (notes.size() == 0) {
			return;
		}
		if (this.cancelled) {
			this.doCancel();
			return;
		}
		final String userRef = Preferences
				.getString(Preferences.Key.SYNC_SERVER_USER_API);
		
		final long newRevision = Preferences
				.getLong(Preferences.Key.LATEST_SYNC_REVISION) + 1;

		this.syncInThread(new Runnable() {
			@Override
			public void run() {
				final OAuthConnection auth = WebDAVSyncService.this
						.getAuthConnection();
				try {
					TLog.v(WebDAVSyncService.TAG,
							"pushing {0} notes to remote service, sending rev #{1}",
							notes.size(), newRevision);
					final String rawResponse = auth.get(userRef);
					if (WebDAVSyncService.this.cancelled) {
						WebDAVSyncService.this.doCancel();
						return;
					}
					try {
						TLog.v(WebDAVSyncService.TAG, "creating JSON");

						final JSONObject data = new JSONObject();
						data.put("latest-sync-revision", newRevision);
						final JSONArray Jnotes = new JSONArray();
						for (final Note note : notes) {
							final JSONObject Jnote = new JSONObject();
							Jnote.put("guid", note.getGuid());
							
							if (note.getTags().contains("system:deleted")) {
								Jnote.put("command", "delete");
							} else { // changed note
								Jnote.put("title",
										XmlUtils.escape(note.getTitle()));
								Jnote.put("note-content", note.getXmlContent());
								Jnote.put("note-content-version", "0.1");
								Jnote.put("last-change-date",
										note.getLastChangeDate());
								Jnote.put("create-date", note.getCreateDate());
								Jnote.put("last-metadata-change-date",
										note.getLastChangeDate()); // TODO: is
								// this
								// different?
							}
							Jnotes.put(Jnote);
						}
						data.put("note-changes", Jnotes);
						
						JSONObject response = new JSONObject(rawResponse);
						if (WebDAVSyncService.this.cancelled) {
							WebDAVSyncService.this.doCancel();
							return;
						}
						final String notesUrl = response.getJSONObject(
								"notes-ref").getString("api-ref");

						TLog.v(WebDAVSyncService.TAG, "put url: {0}", notesUrl);
						
						if (WebDAVSyncService.this.cancelled) {
							WebDAVSyncService.this.doCancel();
							return;
						}

						TLog.v(WebDAVSyncService.TAG,
								"pushing data to remote service: {0}",
								data.toString());
						response = new JSONObject(auth.put(notesUrl,
								data.toString()));

						TLog.v(WebDAVSyncService.TAG, "put response: {0}",
								response.toString());
						WebDAVSyncService.this.latestRemoteRevision = response
								.getLong("latest-sync-revision");
						WebDAVSyncService.this
						.sendMessage(
								SyncService.LATEST_REVISION,
								(int) WebDAVSyncService.this.latestRemoteRevision,
								0);

					} catch (final JSONException e) {
						TLog.e(WebDAVSyncService.TAG, e,
								"Problem parsing the server response");
						WebDAVSyncService.this.sendMessage(
								SyncService.NOTE_PUSH_ERROR, ErrorList
								.createErrorWithContents(
										"JSON parsing", "json", e,
										rawResponse));
						return;
					}
				} catch (final java.net.UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
					return;
				}
				// success, finish sync
				WebDAVSyncService.this.finishSync(true);
			}

		});
	}

	@Override
	protected void pullNote(final String guid) {

		// start loading snowy notes

		TLog.v(WebDAVSyncService.TAG, "pulling remote note");

		final String userRef = Preferences
				.getString(Preferences.Key.SYNC_SERVER_USER_API);

		this.syncInThread(new Runnable() {

			@Override
			public void run() {

				final OAuthConnection auth = WebDAVSyncService.this
						.getAuthConnection();

				try {
					TLog.v(WebDAVSyncService.TAG, "contacting " + userRef);
					String rawResponse = auth.get(userRef);

					try {
						JSONObject response = new JSONObject(rawResponse);
						final String notesUrl = response.getJSONObject(
								"notes-ref").getString("api-ref");

						TLog.v(WebDAVSyncService.TAG, "contacting " + notesUrl
								+ guid);

						rawResponse = auth.get(notesUrl + guid
								+ "?include_notes=true");

						response = new JSONObject(rawResponse);
						JSONArray notes = new JSONArray();
						// Specifications say to look in the notes array if we
						// receive many notes
						// However, if we request one single note, it is saved
						// in the "note" array instead.
						try {
							notes = response.getJSONArray("notes");
						} catch (final JSONException e) {
							notes = response.getJSONArray("note");
						}
						final JSONObject jsonNote = notes.getJSONObject(0);

						TLog.v(WebDAVSyncService.TAG, "parsing remote note");

						WebDAVSyncService.this.insertNote(new Note(jsonNote));

					} catch (final JSONException e) {
						TLog.e(WebDAVSyncService.TAG, e,
								"Problem parsing the server response");
						WebDAVSyncService.this.sendMessage(
								SyncService.NOTE_PULL_ERROR, ErrorList
								.createErrorWithContents(
										"JSON parsing", "json", e,
										rawResponse));
						return;
					}

				} catch (final java.net.UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
					return;
				}

				WebDAVSyncService.this.sendMessage(SyncService.NOTE_PULLED);
			}
		});
	}

	@Override
	public void deleteAllNotes() {

		TLog.v(WebDAVSyncService.TAG, "Deleting Snowy notes");

		final String userRef = Preferences
				.getString(Preferences.Key.SYNC_SERVER_USER_API);
		
		final long newRevision;
		
		if (this.latestLocalRevision > this.latestRemoteRevision) {
			newRevision = this.latestLocalRevision + 1;
		} else {
			newRevision = this.latestRemoteRevision + 1;
		}
		
		this.syncInThread(new Runnable() {

			@Override
			public void run() {

				final OAuthConnection auth = WebDAVSyncService.this
						.getAuthConnection();

				try {
					TLog.v(WebDAVSyncService.TAG, "contacting " + userRef);
					final String rawResponse = auth.get(userRef);
					if (rawResponse == null) {
						return;
					}
					try {
						JSONObject response = new JSONObject(rawResponse);
						final String notesUrl = response.getJSONObject(
								"notes-ref").getString("api-ref");

						TLog.v(WebDAVSyncService.TAG, "contacting " + notesUrl);
						response = new JSONObject(auth.get(notesUrl));

						final JSONArray notes = response.getJSONArray("notes");
						WebDAVSyncService.this.setSyncProgress(50);

						TLog.v(WebDAVSyncService.TAG, "number of notes: {0}",
								notes.length());
						
						final ArrayList<String> guidList = new ArrayList<String>();

						for (int i = 0; i < notes.length(); i++) {
							final JSONObject ajnote = notes.getJSONObject(i);
							guidList.add(ajnote.getString("guid"));
						}

						TLog.v(WebDAVSyncService.TAG, "creating JSON");

						final JSONObject data = new JSONObject();
						data.put("latest-sync-revision", newRevision);
						final JSONArray Jnotes = new JSONArray();
						for (final String guid : guidList) {
							final JSONObject Jnote = new JSONObject();
							Jnote.put("guid", guid);
							Jnote.put("command", "delete");
							Jnotes.put(Jnote);
						}
						data.put("note-changes", Jnotes);

						response = new JSONObject(auth.put(notesUrl,
								data.toString()));

						TLog.v(WebDAVSyncService.TAG, "delete response: {0}",
								response.toString());

						// reset latest sync date so we can push our notes again
						
						WebDAVSyncService.this.latestRemoteRevision = (int) response
								.getLong("latest-sync-revision");
						Preferences.putLong(
								Preferences.Key.LATEST_SYNC_REVISION,
								WebDAVSyncService.this.latestRemoteRevision);
						Preferences.putString(Preferences.Key.LATEST_SYNC_DATE,
								new Time().formatTomboy());
						
					} catch (final JSONException e) {
						TLog.e(WebDAVSyncService.TAG, e,
								"Problem parsing the server response");
						WebDAVSyncService.this.sendMessage(
								SyncService.PARSING_FAILED, ErrorList
								.createErrorWithContents(
										"JSON parsing", "json", e,
										rawResponse));
						WebDAVSyncService.this.setSyncProgress(100);
						return;
					}
				} catch (final java.net.UnknownHostException e) {
					TLog.e(WebDAVSyncService.TAG,
							"Internet connection not available");
					WebDAVSyncService.this.sendMessage(SyncService.NO_INTERNET);
					WebDAVSyncService.this.setSyncProgress(100);
					return;
				}
				WebDAVSyncService.this
				.sendMessage(SyncService.REMOTE_NOTES_DELETED);
			}
		});
	}

	@Override
	public void backupNotes() {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void localSyncComplete() {
		Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION,
				this.latestRemoteRevision);
	}
}
