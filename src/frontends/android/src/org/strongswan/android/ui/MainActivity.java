/*
 * Copyright (C) 2012 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.strongswan.android.ui;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnProfileDataSource;
import org.strongswan.android.logic.CharonVpnService;
import org.strongswan.android.logic.TrustedCertificateManager;
import org.strongswan.android.logic.VpnStateService;
import org.strongswan.android.logic.VpnStateService.State;
import org.strongswan.android.netgroup.secured.Settings_Activity;
import org.strongswan.android.ui.VpnProfileListFragment.OnVpnProfileSelectedListener;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.security.KeyChain;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity implements
		OnVpnProfileSelectedListener {
	public static final String CONTACT_EMAIL = "android@strongswan.org";
	public static final String START_PROFILE = "org.strongswan.android.action.START_PROFILE";
	public static final String EXTRA_VPN_PROFILE_ID = "org.strongswan.android.VPN_PROFILE_ID";
	/** Use "bring your own device" (BYOD) features */
	public static final boolean USE_BYOD = true;
	private static final int PREPARE_VPN_SERVICE = 0;
	private static final String PROFILE_NAME = "org.strongswan.android.MainActivity.PROFILE_NAME";
	private static final String PROFILE_REQUIRES_PASSWORD = "org.strongswan.android.MainActivity.REQUIRES_PASSWORD";
	private static final String PROFILE_RECONNECT = "org.strongswan.android.MainActivity.RECONNECT";
	private static final String DIALOG_TAG = "Dialog";
	private static final String DATABASE_NAME = "strongswan.db";
	private static final String TABLE_VPNPROFILE = "vpnprofile";
	private static final int DATABASE_VERSION = 4;
	private static final String TAG = VpnProfileDataSource.class
			.getSimpleName();
	private static final String[] ALL_COLUMNS = new String[] { VpnProfileDataSource.KEY_ID,
		VpnProfileDataSource.KEY_NAME, VpnProfileDataSource.KEY_GATEWAY, VpnProfileDataSource.KEY_VPN_TYPE, VpnProfileDataSource.KEY_USERNAME, VpnProfileDataSource.KEY_PASSWORD,
		VpnProfileDataSource.KEY_CERTIFICATE, VpnProfileDataSource.KEY_USER_CERTIFICATE, VpnProfileDataSource.KEY_REMOTE_ATTESTATION, };

	private Bundle mProfileInfo;
	private VpnStateService mService;
	private final ServiceConnection mServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceDisconnected(ComponentName name) {
			mService = null;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// viene lanciato subito alla creazione dell'activity
			mService = ((VpnStateService.LocalBinder) service).getService();
			if (START_PROFILE.equals(getIntent().getAction())) {
				// Questo non viene lanciata subito
				startVpnProfile(getIntent());
			}
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		setContentView(R.layout.main);

		this.bindService(new Intent(this, VpnStateService.class),
				mServiceConnection, Service.BIND_AUTO_CREATE);
		ActionBar bar = getActionBar();
		bar.setDisplayShowTitleEnabled(false);

		/* load CA certificates in a background task */
		new CertificateLoadTask().executeOnExecutor(
				AsyncTask.THREAD_POOL_EXECUTOR, false);

		// lancio subito il profilo se sono stato chiamato da VpnProfileDetail
		// poichè è Default NED
		//if (getIntent().getLongExtra(EXTRA_VPN_PROFILE_ID, Long.MIN_VALUE) != Long.MIN_VALUE) {
//
	//		startVpnProfile(getIntent());
	//	}

	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (mService != null) {
			this.unbindService(mServiceConnection);
		}
	}

	/**
	 * Due to launchMode=singleTop this is called if the Activity already exists
	 */
	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);

		if (START_PROFILE.equals(intent.getAction())) {
			startVpnProfile(intent);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main_strong, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_load_cert:
			Intent intent = KeyChain.createInstallIntent();
			startActivity(intent);
			return true;
		case R.id.menu_reload_certs:
			new CertificateLoadTask().executeOnExecutor(
					AsyncTask.THREAD_POOL_EXECUTOR, true);
			return true;
		case R.id.menu_show_log:
			Intent logIntent = new Intent(this, LogActivity.class);
			startActivity(logIntent);
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * Prepare the VpnService. If this succeeds the current VPN profile is
	 * started.
	 * 
	 * @param profileInfo
	 *            a bundle containing the information about the profile to be
	 *            started
	 */
	protected void prepareVpnService(Bundle profileInfo) {
		Intent intent;
		try {
			intent = VpnService.prepare(this);
		} catch (IllegalStateException ex) {
			/*
			 * this happens if the always-on VPN feature (Android 4.2+) is
			 * activated
			 */
			VpnNotSupportedError.showWithMessage(this,
					R.string.vpn_not_supported_during_lockdown);
			return;
		}
		/* store profile info until the user grants us permission */
		mProfileInfo = profileInfo;

		if (intent != null) {
			try {
				startActivityForResult(intent, PREPARE_VPN_SERVICE);
			} catch (ActivityNotFoundException ex) {
				/*
				 * it seems some devices, even though they come with Android 4,
				 * don't have the VPN components built into the system image.
				 * com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog
				 * will not be found then
				 */
				VpnNotSupportedError.showWithMessage(this,
						R.string.vpn_not_supported);
			}
		} else { /* user already granted permission to use VpnService */
			onActivityResult(PREPARE_VPN_SERVICE, RESULT_OK, null);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// Chiamato dopo aver preparato il servizio VPN
		switch (requestCode) {
		case PREPARE_VPN_SERVICE:
			if (resultCode == RESULT_OK && mProfileInfo != null) {
				Intent intent = new Intent(this, CharonVpnService.class);
				intent.putExtras(mProfileInfo);
				this.startService(intent);
			}
			break;
		default:
			super.onActivityResult(requestCode, resultCode, data);
		}
	}

	@Override
	public void onVpnProfileSelected(VpnProfile profile) {
		// quando viene cliccato un NED della lista
		Bundle profileInfo = new Bundle();
		profileInfo.putLong(VpnProfileDataSource.KEY_ID, profile.getId());
		profileInfo.putString(VpnProfileDataSource.KEY_USERNAME,
				profile.getUsername());
		profileInfo.putString(VpnProfileDataSource.KEY_PASSWORD,
				profile.getPassword());
		profileInfo.putBoolean(PROFILE_REQUIRES_PASSWORD, profile.getVpnType()
				.getRequiresUsernamePassword());
		profileInfo.putString(PROFILE_NAME, profile.getName());

		removeFragmentByTag(DIALOG_TAG);

		// AGGIUNTA MIA PER SCRIVERE SU FILE IL FLAG
		try {
			FileOutputStream prova = new FileOutputStream(
					"/data/data/org.strongswan.android/cache/flagRA.txt");
			PrintStream scrivi = new PrintStream(prova);
			if (profile.getRA())
				scrivi.print("v");
			else
				scrivi.print("f");
			scrivi.close();
		} catch (IOException e) {
			System.out.println("Errore: " + e);
			System.exit(1);
		}

		// FINE AGGIUNTA MIA PER SCRIVERE SUL FILE IL FLAG

		// INIZIO VERIFICA REALTIME SE DEFAULT NED
		if (profile.getName().equals("Default NED")) {
			profile.setGateway(intToIp(ottieniDG()));
			VpnProfileDataSource mDataSource = new VpnProfileDataSource(this);
			mDataSource.open();
			long id = profile.getId();
			SQLiteDatabase mDatabase;
			ContentValues values = ContentValuesFromVpnProfile(profile);
			DatabaseHelper mDbHelper = new DatabaseHelper(this);
				mDatabase = mDbHelper.getWritableDatabase();
			
			
			
			mDatabase.update("vpnprofile", values, "_id" + " = " + id,
					null);
			
			mDataSource.updateVpnProfile(profile);
			mDataSource.close();

		}

		// FINE VERIFICA REALTIME SE DEFAULT NED

		if (mService != null && mService.getState() == State.CONNECTED) {
			profileInfo.putBoolean(PROFILE_RECONNECT, mService.getProfile()
					.getId() == profile.getId());

			ConfirmationDialog dialog = new ConfirmationDialog();
			dialog.setArguments(profileInfo);
			dialog.show(this.getFragmentManager(), DIALOG_TAG);
			return;
		}
		startVpnProfile(profileInfo);
	}

	/**
	 * Start the given VPN profile asking the user for a password if required.
	 * 
	 * @param profileInfo
	 *            data about the profile
	 */
	private void startVpnProfile(Bundle profileInfo) {
		if (profileInfo.getBoolean(PROFILE_REQUIRES_PASSWORD)
				&& profileInfo.getString(VpnProfileDataSource.KEY_PASSWORD) == null) {
			LoginDialog login = new LoginDialog();
			login.setArguments(profileInfo);
			login.show(getFragmentManager(), DIALOG_TAG);
			return;
		}
		prepareVpnService(profileInfo);
	}

	/**
	 * Start the VPN profile referred to by the given intent. Displays an error
	 * if the profile doesn't exist.
	 * 
	 * @param intent
	 *            Intent that caused us to start this
	 */
	private void startVpnProfile(Intent intent) {
		long profileId = intent.getLongExtra(EXTRA_VPN_PROFILE_ID, 0);
		if (profileId <= 0) { /* invalid invocation */
			return;
		}
		VpnProfileDataSource dataSource = new VpnProfileDataSource(this);
		dataSource.open();
		VpnProfile profile = dataSource.getVpnProfile(profileId);
		dataSource.close();

		if (profile != null) {
			onVpnProfileSelected(profile);
		} else {
			Toast.makeText(this, R.string.profile_not_found, Toast.LENGTH_LONG)
					.show();
		}
	}

	/**
	 * Class that loads or reloads the cached CA certificates.
	 */
	private class CertificateLoadTask extends
			AsyncTask<Boolean, Void, TrustedCertificateManager> {
		@Override
		protected void onPreExecute() {
			setProgressBarIndeterminateVisibility(true);
		}

		@Override
		protected TrustedCertificateManager doInBackground(Boolean... params) {
			if (params.length > 0 && params[0]) { /*
												 * force a reload of the
												 * certificates
												 */
				return TrustedCertificateManager.getInstance().reload();
			}
			return TrustedCertificateManager.getInstance().load();
		}

		@Override
		protected void onPostExecute(TrustedCertificateManager result) {
			setProgressBarIndeterminateVisibility(false);
		}
	}

	/**
	 * Dismiss dialog if shown
	 */
	public void removeFragmentByTag(String tag) {
		FragmentManager fm = getFragmentManager();
		Fragment login = fm.findFragmentByTag(tag);
		if (login != null) {
			FragmentTransaction ft = fm.beginTransaction();
			ft.remove(login);
			ft.commit();
		}
	}

	/**
	 * Class that displays a confirmation dialog if a VPN profile is already
	 * connected and then initiates the selected VPN profile if the user
	 * confirms the dialog.
	 */
	public static class ConfirmationDialog extends DialogFragment {
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Bundle profileInfo = getArguments();
			int icon = android.R.drawable.ic_dialog_alert;
			int title = R.string.connect_profile_question;
			int message = R.string.replaces_active_connection;
			int button = R.string.connect;

			if (profileInfo.getBoolean(PROFILE_RECONNECT)) {
				icon = android.R.drawable.ic_dialog_info;
				title = R.string.vpn_connected;
				message = R.string.vpn_profile_connected;
				button = R.string.reconnect;
			}

			return new AlertDialog.Builder(getActivity())
					.setIcon(icon)
					.setTitle(
							String.format(getString(title),
									profileInfo.getString(PROFILE_NAME)))
					.setMessage(message)
					.setPositiveButton(button,
							new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog,
										int whichButton) {
									MainActivity activity = (MainActivity) getActivity();
									activity.startVpnProfile(profileInfo);
								}
							})
					.setNegativeButton(android.R.string.cancel,
							new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog,
										int which) {
									dismiss();
								}
							}).create();
		}
	}

	/**
	 * Class that displays a login dialog and initiates the selected VPN profile
	 * if the user confirms the dialog.
	 */
	public static class LoginDialog extends DialogFragment {
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Bundle profileInfo = getArguments();
			LayoutInflater inflater = getActivity().getLayoutInflater();
			View view = inflater.inflate(R.layout.login_dialog, null);
			EditText username = (EditText) view.findViewById(R.id.username);
			username.setText(profileInfo
					.getString(VpnProfileDataSource.KEY_USERNAME));
			final EditText password = (EditText) view
					.findViewById(R.id.password);

			Builder adb = new AlertDialog.Builder(getActivity());
			adb.setView(view);
			adb.setTitle(getString(R.string.login_title));
			adb.setPositiveButton(R.string.login_confirm,
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog,
								int whichButton) {
							MainActivity activity = (MainActivity) getActivity();
							profileInfo.putString(
									VpnProfileDataSource.KEY_PASSWORD, password
											.getText().toString().trim());
							activity.prepareVpnService(profileInfo);
						}
					});
			adb.setNegativeButton(android.R.string.cancel,
					new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							dismiss();
						}
					});
			return adb.create();
		}
	}

	/**
	 * Class representing an error message which is displayed if VpnService is
	 * not supported on the current device.
	 */
	public static class VpnNotSupportedError extends DialogFragment {
		static final String ERROR_MESSAGE_ID = "org.strongswan.android.VpnNotSupportedError.MessageId";

		public static void showWithMessage(Activity activity, int messageId) {
			Bundle bundle = new Bundle();
			bundle.putInt(ERROR_MESSAGE_ID, messageId);
			VpnNotSupportedError dialog = new VpnNotSupportedError();
			dialog.setArguments(bundle);
			dialog.show(activity.getFragmentManager(), DIALOG_TAG);
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			final Bundle arguments = getArguments();
			final int messageId = arguments.getInt(ERROR_MESSAGE_ID);
			return new AlertDialog.Builder(getActivity())
					.setTitle(R.string.vpn_not_supported_title)
					.setMessage(messageId)
					.setCancelable(false)
					.setPositiveButton(android.R.string.ok,
							new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog,
										int id) {
									dialog.dismiss();
								}
							}).create();
		}
	}

	public int ottieniDG() {
		final WifiManager manager = (WifiManager) this
				.getSystemService(android.content.Context.WIFI_SERVICE);
		final DhcpInfo dhcp = manager.getDhcpInfo();
		return dhcp.gateway;
	}

	public String intToIp(int addr) {
		// trasformo intero in stringa IP
		return ((addr & 0xFF) + "." + ((addr >>>= 8) & 0xFF) + "."
				+ ((addr >>>= 8) & 0xFF) + "." + ((addr >>>= 8) & 0xFF));
	}
	private ContentValues ContentValuesFromVpnProfile(VpnProfile profile) {
		ContentValues values = new ContentValues();
		values.put(VpnProfileDataSource.KEY_NAME, profile.getName());
		values.put(VpnProfileDataSource.KEY_GATEWAY, profile.getGateway());
		values.put(VpnProfileDataSource.KEY_VPN_TYPE, profile.getVpnType().getIdentifier());
		values.put(VpnProfileDataSource.KEY_USERNAME, profile.getUsername());
		values.put(VpnProfileDataSource.KEY_PASSWORD, profile.getPassword());
		values.put(VpnProfileDataSource.KEY_CERTIFICATE, profile.getCertificateAlias());
		values.put(VpnProfileDataSource.KEY_USER_CERTIFICATE, profile.getUserCertificateAlias());
		if (profile.getRA()==true) values.put(VpnProfileDataSource.KEY_REMOTE_ATTESTATION, "TRUE");
		else values.put(VpnProfileDataSource.KEY_REMOTE_ATTESTATION, "FALSE");
		return values;
	}
	
	private static class DatabaseHelper extends SQLiteOpenHelper {
		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase database) {
			database.execSQL(VpnProfileDataSource.DATABASE_CREATE);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
					+ newVersion);
			if (oldVersion < 2) {
				db.execSQL("ALTER TABLE " + TABLE_VPNPROFILE + " ADD "
						+ VpnProfileDataSource.KEY_USER_CERTIFICATE + " TEXT;");
			}
			if (oldVersion < 3) {
				db.execSQL("ALTER TABLE " + TABLE_VPNPROFILE + " ADD "
						+ VpnProfileDataSource.KEY_VPN_TYPE + " TEXT DEFAULT '';");
			}
			if (oldVersion < 4) { /*
								 * remove NOT NULL constraint from username
								 * column
								 */
				updateColumns(db);
			}
		}

		private void updateColumns(SQLiteDatabase db) {
			db.beginTransaction();
			try {
				db.execSQL("ALTER TABLE " + TABLE_VPNPROFILE
						+ " RENAME TO tmp_" + TABLE_VPNPROFILE + ";");
				db.execSQL(VpnProfileDataSource.DATABASE_CREATE);
				StringBuilder insert = new StringBuilder("INSERT INTO "
						+ TABLE_VPNPROFILE + " SELECT ");
				SQLiteQueryBuilder.appendColumns(insert, ALL_COLUMNS);
				db.execSQL(insert.append(" FROM tmp_" + TABLE_VPNPROFILE + ";")
						.toString());
				db.execSQL("DROP TABLE tmp_" + TABLE_VPNPROFILE + ";");
				db.setTransactionSuccessful();
			} finally {
				db.endTransaction();
			}
		}
	}
}
