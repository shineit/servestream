/*
 * ServeStream: A HTTP stream browser/player for Android
 * Copyright 2012 William Seemann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sourceforge.servestream.activity;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import net.sourceforge.servestream.service.MediaPlaybackService;
import net.sourceforge.servestream.transport.AbsTransport;
import net.sourceforge.servestream.transport.TransportFactory;
import net.sourceforge.servestream.utils.MusicUtils;
import net.sourceforge.servestream.utils.MusicUtils.ServiceToken;

import net.sourceforge.servestream.R;
import net.sourceforge.servestream.alarm.Alarm;
import net.sourceforge.servestream.bean.UriBean;
import net.sourceforge.servestream.dbutils.StreamDatabase;
import net.sourceforge.servestream.utils.PreferenceConstants;
import net.sourceforge.servestream.utils.UpdateHelper;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.SharedPreferences.Editor;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;

import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import net.sourceforge.servestream.utils.DetermineActionTask;

public class URLListActivity extends ListActivity implements ServiceConnection,
				DetermineActionTask.MusicRetrieverPreparedListener {
	
	public final static String TAG = URLListActivity.class.getName();	
	
 	private static final int MESSAGE_UPDATE_LIST = 1;

    private static final String STATE_DETERMINE_INTENT_IN_PROGRESS = "net.sourceforge.servestream.inprogress";
    private static final String STATE_DETERMINE_INTENT_STREAM = "net.sourceforge.servestream.stream";
    private static final String STATE_MAKING_SHORTCUT = "net.sourceforge.servestream.makingshortcut";
	
    private final static int DETERMINE_INTENT_TASK = 1;
	private final static int MISSING_BARCODE_SCANNER = 2;
	private final static int UNSUPPORTED_SCANNED_INTENT = 3;
	private final static int RATE_APPLICATION = 4;
	
	private TextView mQuickconnect = null;
	private Button mGoButton = null;
	
	protected StreamDatabase mStreamdb = null;
	protected LayoutInflater mInflater = null;
	
	private boolean mSortedByName = false;
	
	private SharedPreferences mPreferences = null;
	
	protected boolean mMakingShortcut = false;
	
	private boolean mActivityVisible = true;
	
    private ServiceToken mToken;
	
    private DetermineActionTask mDetermineActionTask;
    
	protected Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			URLListActivity.this.handleMessage(msg);
		}
	};
	
	protected Handler mQueueHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			MusicUtils.addToCurrentPlaylist(URLListActivity.this, (long []) msg.obj);
		}
	};
	
	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		setContentView(R.layout.act_urllist);

		this.setTitle(String.format("%s: %s",
				getResources().getText(R.string.app_name),
				getResources().getText(R.string.title_url_list)));
		
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
		
        mToken = MusicUtils.bindToService(this, this);		
		
		mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		
		// If the intent is a request to create a shortcut, we'll do that and exit
		mMakingShortcut = Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction());
		
		// If the orientation is changed while making a shortcut make sure to recreate
		// the activity accordingly
		if (icicle != null) {
			mMakingShortcut = icicle.getBoolean(STATE_MAKING_SHORTCUT);
		}
		
		mQuickconnect = (TextView) this.findViewById(R.id.front_quickconnect);
		mQuickconnect.setVisibility(mMakingShortcut ? View.GONE : View.VISIBLE);
		mQuickconnect.setOnKeyListener(new OnKeyListener() {

			public boolean onKey(View v, int keyCode, KeyEvent event) {

				if(event.getAction() == KeyEvent.ACTION_UP)
					return false;
				
				if(keyCode != KeyEvent.KEYCODE_ENTER)
					return false;
			    
			    return processUri(mQuickconnect.getText().toString());
			}
		});
		
		// start thread to check for new version
		new UpdateHelper(this);
		
		// see if the user wants to rate the application after 5 uses
		if (getIntent().getType() == null &&
				getIntent().getData() == null &&
				getIntent().getExtras() == null) {
			int rateApplicationFlag = mPreferences.getInt(PreferenceConstants.RATE_APPLICATION_FLAG, 0);
			if (rateApplicationFlag != -1) {
				rateApplicationFlag++;
				Editor ed = mPreferences.edit();
				ed.putInt(PreferenceConstants.RATE_APPLICATION_FLAG, rateApplicationFlag);
				ed.commit();
				if (rateApplicationFlag == 5) {
					showDialog(RATE_APPLICATION);
				}
			}
		}
		
		// connect with streams database and populate list
		mStreamdb = new StreamDatabase(this);
		
		mSortedByName = mPreferences.getBoolean(PreferenceConstants.SORT_BY_NAME, false);
		
		ListView list = this.getListView();
		list.setOnItemClickListener(new OnItemClickListener() {
			public synchronized void onItemClick(AdapterView<?> parent, View view, int position, long id) {

				UriBean uriBean = (UriBean) parent.getAdapter().getItem(position);
				
				if (mMakingShortcut) {
					Intent contents = new Intent(Intent.ACTION_VIEW);
					contents.setType("net.sourceforge.servestream/" + uriBean.getUri());
					contents.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
					
					// create shortcut if requested
					ShortcutIconResource icon = Intent.ShortcutIconResource.fromContext(URLListActivity.this, R.drawable.icon);

					Intent intent = new Intent();
					intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, contents);
					intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, uriBean.getNickname());
					intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, icon);

					setResult(RESULT_OK, intent);
					finish();
				} else {
					processUri(uriBean.getUri().toString());
				}
			}
		});

		this.registerForContextMenu(list);

		mGoButton = (Button) this.findViewById(R.id.go_button);
		mGoButton.setVisibility(mMakingShortcut ? View.GONE : View.VISIBLE);
		mGoButton.setOnClickListener(new OnClickListener() {

			public void onClick(View arg0) {
				processUri(mQuickconnect.getText().toString());
			}
		});
		
		this.mInflater = LayoutInflater.from(this);
		
		handleIntentData();
	}
	
	private void handleIntentData() {
		String intentUri = null;
		String contentType = null;
		
		Intent intent = getIntent();
		
        // check to see if we were called from a home screen shortcut
		if ((contentType = intent.getType()) != null) {
			if (contentType.contains("net.sourceforge.servestream/")) {
				intentUri = intent.getType().toString().replace("net.sourceforge.servestream/", "");
				processUri(intentUri);
				setIntent(new Intent());
				return;
			}
		}
		
		// check to see if we were called by clicking on a URL
		if (intent.getData() != null) {
			intentUri = intent.getData().toString();
		}
		
		// check to see if the application was opened from a share intent
		if (intent.getExtras() != null && intent.getExtras().getCharSequence(Intent.EXTRA_TEXT) != null) {
			intentUri = intent.getExtras().getCharSequence(Intent.EXTRA_TEXT).toString();
		}

		if (intentUri != null) {
			try {
				intentUri = URLDecoder.decode(intentUri, "UTF-8");
				mQuickconnect.setText(intentUri);
				processUri(intentUri);
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		
		setIntent(new Intent());
	}
	
    @Override
    public void onNewIntent(Intent intent) {
    	super.onNewIntent(intent);
		
        setIntent(intent);        
        handleIntentData();
    }
	
	@Override
	public void onStart() {
		super.onStart();
		
		updateList();
		
		if(mStreamdb == null) {
			mStreamdb = new StreamDatabase(this);
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        registerReceiver(mTrackListListener, f);
		
		mActivityVisible = true;
	}
	
	@Override
	public void onPause() {
		super.onResume();
		
        unregisterReceiver(mTrackListListener);
		
		mActivityVisible = false;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();

		if(mStreamdb != null) {
			mStreamdb.close();
			mStreamdb = null;
		}
		
        MusicUtils.unbindFromService(mToken);
	}
	
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        
        restoreDetermineIntentTask(savedInstanceState);
    }
	
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        
        saveDetermineIntentTask(outState);
    }
	
    private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.updateNowPlaying(URLListActivity.this);
        }
    };
    
    private void saveDetermineIntentTask(Bundle outState) {
    	outState.putBoolean(STATE_MAKING_SHORTCUT, mMakingShortcut);
    	
        final DetermineActionTask task = mDetermineActionTask;
        if (task != null && task.getStatus() != AsyncTask.Status.FINISHED) {
            final String uri = task.getUri().toString();
            task.cancel(true);
			try {
				removeDialog(DETERMINE_INTENT_TASK);
			} catch (Exception ex) {
			}

            if (uri != null) {
                outState.putBoolean(STATE_DETERMINE_INTENT_IN_PROGRESS, true);
                outState.putString(STATE_DETERMINE_INTENT_STREAM, uri);
            }

            mDetermineActionTask = null;
        }
    }
    
    private void restoreDetermineIntentTask(Bundle savedInstanceState) {
        if (savedInstanceState.getBoolean(STATE_DETERMINE_INTENT_IN_PROGRESS)) {
            final String uri = savedInstanceState.getString(STATE_DETERMINE_INTENT_STREAM);
            processUri(uri);
        }
    }
    
	@Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	switch(item.getItemId()) {
    		case (R.id.menu_item_sort_by_name):
				mSortedByName = true;
				updateList();
    			break;
    		case (R.id.menu_item_sort_by_date):
				mSortedByName = false;
				updateList();
    			break;
        	case (R.id.menu_item_settings):
        		startActivity(new Intent(URLListActivity.this, SettingsActivity.class));
        		break;
        	case (R.id.menu_item_help):
        		startActivity(new Intent(URLListActivity.this, HelpActivity.class));
        		break;
            case (R.id.menu_item_alarms):
                startActivity(new Intent(this, AlarmClockActivity.class));
                return true;
            case (R.id.menu_item_scan):
            	try {
            		Intent intent = new Intent("com.google.zxing.client.android.SCAN");
            		intent.setPackage("com.google.zxing.client.android");
            		intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
            		startActivityForResult(intent, 0);
            	} catch (ActivityNotFoundException ex) {
            		showDialog(MISSING_BARCODE_SCANNER);
            	}
            	return true;
            case (R.id.menu_item_donate):
	        	try {
	        		Intent intent = new Intent(Intent.ACTION_VIEW);
	        		intent.setData(Uri.parse("market://details?id=net.sourceforge.donate.servestream"));
	        		startActivity(intent);
	        	} catch (ActivityNotFoundException ex ) {
	        		// the market couldn't be opening or the application couldn't be found
	        		// lets take the user to the project's webpage instead.
	        		Intent intent = new Intent(Intent.ACTION_VIEW);
	        		intent.setData(Uri.parse("https://sourceforge.net/p/servestream/donate/"));
	        		startActivity(intent);
	            }
            	return true;
    	}
    	
		return false;
    }

	protected Dialog onCreateDialog(int id) {
	    Dialog dialog;
	    ProgressDialog progressDialog = null;
    	AlertDialog.Builder builder;
    	AlertDialog alertDialog;
	    switch(id) {
	    case DETERMINE_INTENT_TASK:
	    	progressDialog = new ProgressDialog(URLListActivity.this);
	    	progressDialog.setMessage(getString(R.string.opening_url_message));
	    	progressDialog.setOnCancelListener(new OnCancelListener() {

				public void onCancel(DialogInterface dialog) {
					if (mDetermineActionTask != null) {
						mDetermineActionTask.cancel(true);
						mDetermineActionTask = null;
						try {
							removeDialog(DETERMINE_INTENT_TASK);
						} catch (Exception ex) {
						}
					}
				}	
			});
	    	return progressDialog;
	    case MISSING_BARCODE_SCANNER:
	    	builder = new AlertDialog.Builder(this);
	    	builder.setMessage(R.string.find_barcode_scanner_message)
	    	       .setCancelable(true)
	    	       .setPositiveButton(R.string.find_pos, new DialogInterface.OnClickListener() {
	    	           public void onClick(DialogInterface dialog, int id) {
	    	        	   try {
	    	        		   Intent intent = new Intent(Intent.ACTION_VIEW);
	    	        		   intent.setData(Uri.parse("market://details?id=com.google.zxing.client.android"));
	    	        		   startActivity(intent);
	    	        	   } catch (ActivityNotFoundException ex ) {
	    	        		   // the market couldn't be opening or the application couldn't be found
	    	        		   // lets take the user to the project's webpage instead.
	    	        		   Intent intent = new Intent(Intent.ACTION_VIEW);
	    	        		   intent.setData(Uri.parse("http://code.google.com/p/zxing/downloads/list"));
	    	        		   startActivity(intent);
	    	        	   }
	    	           }
	    	       })
	    	       .setNegativeButton(R.string.find_neg, new DialogInterface.OnClickListener() {
	    	           public void onClick(DialogInterface dialog, int id) {
	    	                dialog.cancel();
	    	           }
	    	       });
	    	alertDialog = builder.create();
	    	return alertDialog;
	    case UNSUPPORTED_SCANNED_INTENT:
	    	builder = new AlertDialog.Builder(this);
	    	builder.setMessage(R.string.unsupported_scanned_intent_message)
	    	       .setCancelable(true)
	    	       .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
	    	           public void onClick(DialogInterface dialog, int id) {
	    	                dialog.cancel();
	    	           }
	    	       });
	    	alertDialog = builder.create();
	    	return alertDialog;
	    case RATE_APPLICATION:
	        Editor ed = mPreferences.edit();
	        ed.putInt(PreferenceConstants.RATE_APPLICATION_FLAG, -1);
	        ed.commit();
	    	builder = new AlertDialog.Builder(this);
	    	builder.setMessage(R.string.rate_application)
	    	       .setCancelable(true)
	    	       .setPositiveButton(R.string.rate_pos, new DialogInterface.OnClickListener() {
	    	           public void onClick(DialogInterface dialog, int id) {
	    	        	   try {
	    	        		   Intent intent = new Intent(Intent.ACTION_VIEW);
	    	        		   intent.setData(Uri.parse("market://details?id=net.sourceforge.servestream"));
	    	        		   startActivity(intent);
	    	        	   } catch (ActivityNotFoundException ex ) {
	    	        		   // the market couldn't be opening or the application couldn't be found
	    	        		   // lets take the user to the project's webpage instead.
	    	        		   Intent intent = new Intent(Intent.ACTION_VIEW);
	    	        		   intent.setData(Uri.parse("http://sourceforge.net/projects/servestream/"));
	    	        		   startActivity(intent);
	    	        	   }
	    	           }
	    	       })
	    	       .setNegativeButton(R.string.rate_neg, new DialogInterface.OnClickListener() {
	    	           public void onClick(DialogInterface dialog, int id) {
	    	                dialog.cancel();
	    	           }
	    	       });
	    	alertDialog = builder.create();
	    	return alertDialog;
	    default:
	        dialog = null;
	    }
	    return dialog;
	}
	
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);

		// don't offer menus when creating shortcut
		if (mMakingShortcut) {
			return true;
		}

		menu.getItem(0).setVisible(!mSortedByName);
		menu.getItem(1).setVisible(mSortedByName);

		return true;
	}
	
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
		// don't offer menus when creating shortcut
		if (mMakingShortcut) {
			return true;
		}
    	
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.url_list_menu, menu);
        return true;
    }
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
		// don't offer menus when creating shortcut
		if (mMakingShortcut) {
			return;
		}
		
		// create menu to handle editing, deleting and sharing of URLs
		AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
		final UriBean uri = (UriBean) this.getListView().getItemAtPosition(info.position);

		// set the menu to the name of the URL
		menu.setHeaderTitle(uri.getNickname());

		// edit the URL
		MenuItem edit = menu.add(R.string.edit);
		edit.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem arg0) {
				Intent intent = new Intent(URLListActivity.this, StreamEditorActivity.class);
				intent.putExtra(Intent.EXTRA_TITLE, uri.getId());
				URLListActivity.this.startActivity(intent);
				return true;
			}
		});
		
		// delete the URL
		MenuItem delete = menu.add(R.string.delete);
		delete.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem item) {
				// prompt user to make sure they really want this
				new AlertDialog.Builder(URLListActivity.this)
					.setMessage(getString(R.string.delete_message, uri.getNickname()))
					.setPositiveButton(R.string.delete_pos, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							mStreamdb.deleteUri(uri);
							ContentResolver resolver = getContentResolver();
							resolver.update(
									Alarm.Columns.CONTENT_URI,
									null, null, new String[] { String.valueOf(uri.getId()) });
							mHandler.sendEmptyMessage(MESSAGE_UPDATE_LIST);
						}
						})
					.setNegativeButton(android.R.string.cancel, null).create().show();
				return true;
			}
		});
		
		// add to playlist
		MenuItem add = menu.add(R.string.add_to_playlist);
		add.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem item) {
				MusicUtils.addToCurrentPlaylistFromURL(URLListActivity.this, uri, mQueueHandler);
				return true;
			}
		});
		
		// share the URL
		MenuItem share = menu.add(R.string.share);
		share.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			public boolean onMenuItemClick(MenuItem item) {
				String url = uri.getUri().toString();
				String appName = getString(R.string.app_name);
				
				Intent intent = new Intent(Intent.ACTION_SEND);
				intent.setType("text/plain");
				intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_signature, url, appName));
				startActivity(Intent.createChooser(intent, getString(R.string.title_share)));
				return true;
			}
		});
	}

	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
	    if (requestCode == 0) {
	        if (resultCode == RESULT_OK) {
	            String contents = intent.getStringExtra("SCAN_RESULT");
	            String format = intent.getStringExtra("SCAN_RESULT_FORMAT");
	            // Handle successful scan
	            Log.v(TAG, contents.toString());
	            Log.v(TAG, format.toString());
			    mQuickconnect.setText(contents);
	        } else if (resultCode == RESULT_CANCELED) {
	            // Handle cancel
	        }
	    }
	}
	
	private void handleMessage(Message message) {
		switch (message.what) {
			case MESSAGE_UPDATE_LIST:
				updateList();
				break;
		}
	}
	
	private boolean processUri(String input) {
		hideKeyboard();
		
		Uri uri = TransportFactory.getUri(input);

		if (uri == null) {
			mQuickconnect.setError(getString(R.string.invalid_url_message));
			return false;
		}

		UriBean uriBean = TransportFactory.findUri(mStreamdb, uri);
		if (uriBean == null) {
			uriBean = TransportFactory.getTransport(uri.getScheme()).createUri(uri);
			
			AbsTransport transport = TransportFactory.getTransport(uriBean.getProtocol());
			transport.setUri(uriBean);
			if (mPreferences.getBoolean(PreferenceConstants.AUTOSAVE, true) && transport.shouldSave()) {
				mStreamdb.saveUri(uriBean);
			}
		}
		
	    showDialog(DETERMINE_INTENT_TASK);
	    mDetermineActionTask = new DetermineActionTask(this, uriBean, this);
	    mDetermineActionTask.execute();
		
		return true;
	}
	
	private void updateList() {
		if (mPreferences.getBoolean(PreferenceConstants.SORT_BY_NAME, false) != mSortedByName) {
			Editor edit = mPreferences.edit();
			edit.putBoolean(PreferenceConstants.SORT_BY_NAME, mSortedByName);
			edit.commit();
		}
		
		List<UriBean> uris = new ArrayList<UriBean>();

		if (mStreamdb == null) {
			mStreamdb = new StreamDatabase(this);   
		}

		uris = mStreamdb.getUris(mSortedByName);

		UriAdapter adapter = new UriAdapter(this, uris);

		this.setListAdapter(adapter);
	}
	
	class UriAdapter extends ArrayAdapter<UriBean> {
		
		private List<UriBean> uris;

		class ViewHolder {
			public TextView nickname;
			public TextView caption;
		}

		public UriAdapter(Context context, List<UriBean> uris) {
			super(context, R.layout.item_stream, uris);

			this.uris = uris;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			ViewHolder holder;

			if (convertView == null) {
				convertView = mInflater.inflate(R.layout.item_stream, null, false);

				holder = new ViewHolder();

				holder.nickname = (TextView)convertView.findViewById(android.R.id.text1);
				holder.caption = (TextView)convertView.findViewById(android.R.id.text2);

				convertView.setTag(holder);
			} else
				holder = (ViewHolder) convertView.getTag();

			UriBean uri = uris.get(position);

			holder.nickname.setText(uri.getNickname());

			Context context = convertView.getContext();

			holder.nickname.setTextAppearance(context, android.R.attr.textAppearanceLarge);
			holder.caption.setTextAppearance(context, android.R.attr.textAppearanceSmall);

			long now = System.currentTimeMillis() / 1000;

			String lastConnect = context.getString(R.string.bind_never);
			if (uri.getLastConnect() > 0) {
				int minutes = (int)((now - uri.getLastConnect()) / 60);
				if (minutes >= 60) {
					int hours = (minutes / 60);
					if (hours >= 24) {
						int days = (hours / 24);
						lastConnect = context.getString(R.string.bind_days, days);
					} else
						lastConnect = context.getString(R.string.bind_hours, hours);
				} else
					lastConnect = context.getString(R.string.bind_minutes, minutes);
			}

			holder.caption.setText(lastConnect);

			return convertView;
		}
	}
	
	/**
	 * Hides the keyboard
	 */
	private void hideKeyboard() {
		InputMethodManager inputManager = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		View textView = mQuickconnect;
		inputManager.hideSoftInputFromWindow(textView.getWindowToken(), 0);
	}
	
	private void showUrlNotOpenedToast() {
		if (mActivityVisible) {
			Toast.makeText(this, R.string.url_not_opened_message, Toast.LENGTH_SHORT).show();
		}
	}
	
	public void onServiceConnected(ComponentName arg0, IBinder arg1) {
		MusicUtils.updateNowPlaying(this);
	}

	public void onServiceDisconnected(ComponentName arg0) {
		finish();
	}
	
	public void onMusicRetrieverPrepared(String action, UriBean uri, long[] list) {
		try {
			removeDialog(DETERMINE_INTENT_TASK);
		} catch (Exception ex) {
		}
		
		if (action.equals(DetermineActionTask.URL_ACTION_UNDETERMINED)) {
			showUrlNotOpenedToast();
		} else if (action.equals(DetermineActionTask.URL_ACTION_BROWSE)) {
			if (mPreferences.getBoolean(PreferenceConstants.AUTOSAVE, true)) {
				mStreamdb.touchUri(uri);
			}
			
			Intent intent = new Intent(URLListActivity.this, BrowserActivity.class);
			intent.setData(uri.getScrubbedUri());
			
			URLListActivity.this.startActivity(intent);			
		} else if (action.equals(DetermineActionTask.URL_ACTION_PLAY)) {
			if (mPreferences.getBoolean(PreferenceConstants.AUTOSAVE, true)) {
				mStreamdb.touchUri(uri);
			}
			
			MusicUtils.playAll(URLListActivity.this, list, 0);        
		}
	}
}
