/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2009, Benoit Garret <benoit.garret_launchpad@gadz.org>
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
package org.tomdroid.ui;

import java.util.ArrayList;

import org.tomdroid.R;
import org.tomdroid.sync.ServiceAuth;
import org.tomdroid.sync.SyncManager;
import org.tomdroid.sync.SyncService;
import org.tomdroid.util.Preferences;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.Preference.OnPreferenceChangeListener;
import android.util.Log;
import android.widget.Toast;

public class PreferencesActivity extends PreferenceActivity {
	
	private static final String TAG = "PreferencesActivity";
	
	// TODO: put the various preferences in fields and figure out what to do on activity suspend/resume
	private EditTextPreference syncServer = null;
	private ListPreference syncService = null;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
		
		// Fill the Preferences fields
		syncServer = (EditTextPreference)findPreference(Preferences.Key.SYNC_SERVER.getName());
		syncService = (ListPreference)findPreference(Preferences.Key.SYNC_SERVICE.getName());
		
		// Set the default values if nothing exists
		this.setDefaults();
		
		// Fill the services combo list
		this.fillServices();
		
		// Enable or disable the server field depending on the selected sync service
		setServer(syncService.getValue());
		
		syncService.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				
				setServer((String)newValue);
				return true;
			}
		});
		
		// Re-authenticate if the sync server changes
		syncServer.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

			public boolean onPreferenceChange(Preference preference,
					Object serverUri) {
				
				if (serverUri == null) {
					Toast.makeText(PreferencesActivity.this,
							getString(R.string.prefServerEmpty),
							Toast.LENGTH_SHORT).show();
					return false;
				}
			    
				authenticate((String) serverUri);
				return true;
			}
			
		});
		
	}
	
	private void authenticate(String serverUri) {

		// update the value before doing anything
		Preferences.putString(Preferences.Key.SYNC_SERVER, serverUri);

		SyncService currentService = SyncManager.getInstance().getCurrentService();

		if (!currentService.needsAuth()) {
			return;
		}

		// service needs authentication
		Log.i(TAG, "Creating dialog");

		final ProgressDialog authProgress = ProgressDialog.show(this, "",
				"Authenticating. Please wait...", true, false);

		Handler handler = new Handler() {

			@Override
			public void handleMessage(Message msg) {

				boolean wasSuccsessful = false;
				Uri authorizationUri = (Uri) msg.obj;
				if (authorizationUri != null) {

					Intent i = new Intent(Intent.ACTION_VIEW, authorizationUri);
					startActivity(i);
					wasSuccsessful = true;

				} else {
					// Auth failed, don't update the value
					wasSuccsessful = false;
				}

				if (authProgress != null)
					authProgress.dismiss();

				if (wasSuccsessful) {
					resetLocalDatabase();
				} else {
					connectionFailed();
				}
			}
		};

		((ServiceAuth) currentService).getAuthUri(serverUri, handler);
	}
	
	private void fillServices()
	{
		ArrayList<SyncService> availableServices = SyncManager.getInstance().getServices();
		CharSequence[] entries = new CharSequence[availableServices.size()];
		CharSequence[] entryValues = new CharSequence[availableServices.size()];
		
		for (int i = 0; i < availableServices.size(); i++) {
			entries[i] = availableServices.get(i).getDescription();
			entryValues[i] = availableServices.get(i).getName();
		}
		
		syncService.setEntries(entries);
		syncService.setEntryValues(entryValues);
	}
	
	private void setDefaults()
	{
		String defaultServer = (String)Preferences.Key.SYNC_SERVER.getDefault();
		syncServer.setDefaultValue(defaultServer);
		if(syncServer.getText() == null)
			syncServer.setText(defaultServer);

		String defaultService = (String)Preferences.Key.SYNC_SERVICE.getDefault();
		syncService.setDefaultValue(defaultService);
		if(syncService.getValue() == null)
			syncService.setValue(defaultService);
	
	}

	private void setServer(String syncServiceKey) {

		SyncService service = SyncManager.getInstance().getService(syncServiceKey);

		if (service == null)
			return;

		if (!service.needsAuth()){
			resetLocalDatabase();
		}
		
		syncServer.setEnabled(service.needsServer());
		syncService.setSummary(service.getDescription());

	}
		
	private void connectionFailed() {
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.prefSyncConnectionFailed))
			.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}})
			.show();
	}

	//TODO use LocalStorage wrapper from two-way-sync branch when it get's merged
	private void resetLocalDatabase() {
		getContentResolver().delete(Tomdroid.CONTENT_URI, null, null);
		Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION, 0);
	}

}