/**
 *    Copyright 2009 Bram de Kruijff <bdekruijff [at] gmail [dot] com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package net.atoom.android.tt2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import net.atoom.android.tt2.util.BoundStack;
import net.atoom.android.tt2.util.LogBridge;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.ads.AdRequest;
import com.google.ads.AdView;

public final class TTActivity extends Activity {

	public static final String LOGGING_TAG = "AtoomTT";

	private static final String CONTENT_STARTPAGEURL = "http://teletekst.nos.nl/tekst/101-01.html";

	private static final String PREFS_CURRENT_URL = "currentUrl";
	private static final String PREFS_HOMEPAGE_URL = "homepageUrl";

	private static final String TEMPLATE_FILENAME = "template.html";
	private static final String TEMPLATE_PLACEHOLDER = "[pageContent]";

	private static final int MENU_ABOUT = 1;
	private static final int MENU_SETHOME = 2;
	private static final int MENU_CLOSE = 3;

	private static final int HISTORY_SIZE = 50;
	private static final long RELOAD_INTERVAL_MS = 60000;

	private static final int MESSAGE_UPDATE_ADVIEW = 1;

	private final PageLoader myPageLoader;
	private final Handler myHandler;
	private final BoundStack<PageEntity> myHistoryStack;

	private MainWebViewAnimator myMainWebViewAnimator;
	private EditText myPageEditText;

	private Button myHomeButton;
	private Button myNextPageButton;
	private Button myNextSubPageButton;
	private Button myPrevPageButton;
	private Button myPrevSubPageButton;

	private String myStartPageUrl;
	private String myHomePageUrl;
	private String myTemplate;
	private PageEntity myCurrentPageEntity;
	private int myPageLoadCount = 0;

	private volatile Location myLocation = null;
	private volatile boolean isStopped = false;

	public TTActivity() {
		myPageLoader = new PageLoader();
		myHistoryStack = new BoundStack<PageEntity>(HISTORY_SIZE);

		myHandler = new Handler() {
			@Override
			public void handleMessage(Message msg) {
				switch (msg.arg1) {
				case MESSAGE_UPDATE_ADVIEW:
					updateAdView();
					break;
				default:
					break;
				}
			}
		};
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		loadPreferences();
		loadTemplate();

		initGraphics();
		initEditText();
		initButtons();
		initMainWebViewAnimator();

                // TODO remove this in the final version
                // http://android-developers.blogspot.com/2010/12/new-gingerbread-api-strictmode.html
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);

		myHandler.postDelayed(new Runnable() {
			public void run() {
				loadPageUrl(myStartPageUrl, true);
			}
		}, 100);
		myHandler.postDelayed(new Runnable() {
			public void run() {
				initLocation();
//				updateAdView();
				
			}
		}, 100);
	}

	@Override
	protected void onStop() {
		isStopped = true;
		super.onStop();
		storePreferences();
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			return handleBackButton();
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public void onConfigurationChanged(Configuration config) {
		// Do nothing, this is to prevent the activity from being restarted when
		// the keyboard opens.
		super.onConfigurationChanged(config);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, MENU_SETHOME, 0, R.string.menu_sethomepage);
		menu.add(0, MENU_ABOUT, 1, R.string.menu_about);
		menu.add(0, MENU_CLOSE, 2, R.string.menu_close);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case MENU_ABOUT:
			return handleAboutDialog();
		case MENU_SETHOME:
			return handleSetHomePage();
		case MENU_CLOSE:
			isStopped = true;
			storePreferences();
			finish();
		}
		return false;
	}

	public synchronized void loadPageUrl(final String pageUrl, final boolean updateHistory) {

		myPageLoadCount++;
		PageEntity previousPageEntity = myCurrentPageEntity;

		myCurrentPageEntity = myPageLoader.loadPage(pageUrl);
		if (myCurrentPageEntity == null) {
			myCurrentPageEntity = previousPageEntity;
			Toast.makeText(getApplicationContext(), R.string.toast_pagenotfound, Toast.LENGTH_SHORT).show();
			return;
		}

		if (previousPageEntity != null && updateHistory && !previousPageEntity.getPageUrl().equals(pageUrl)) {
			myHistoryStack.push(previousPageEntity);
		}

		updateEditText();
		updateButtons();
		updateWebView();

		// Prefetching neighbours
		myHandler.postDelayed(new Runnable() {
			public void run() {
				myPageLoader.loadPage(myCurrentPageEntity.getNextPageUrl());
				myPageLoader.loadPage(myCurrentPageEntity.getPrevPageUrl());
				myPageLoader.loadPage(myCurrentPageEntity.getNextSubPageUrl());
				myPageLoader.loadPage(myCurrentPageEntity.getPrevSubPageUrl());
			}
		}, 100);

		// Scheduling reload
		myHandler.postDelayed(new ReloadRunnable(this, myPageLoadCount), RELOAD_INTERVAL_MS);
	}

	public synchronized void reloadPageUrl(final int pageLoadCount) {
		if (myPageLoadCount == pageLoadCount && !isStopped) {
			if (LogBridge.isLoggable())
				LogBridge.i("Reloading...");
			Toast.makeText(getApplicationContext(), R.string.toast_pagereload, Toast.LENGTH_SHORT).show();
			loadPageUrl(myCurrentPageEntity.getPageUrl(), false);
		} else {
			if (LogBridge.isLoggable())
				LogBridge.i("Aborting reload");
		}
	}

	public synchronized void loadNextPage() {
		if (myCurrentPageEntity != null) {
			if (myCurrentPageEntity.getNextSubPageUrl() != null) {
				loadPageUrl(myCurrentPageEntity.getNextSubPageUrl(), true);
			} else {
				if (myCurrentPageEntity.getNextPageUrl() != null) {
					loadPageUrl(myCurrentPageEntity.getNextPageUrl(), true);
				}
			}
		}
	}

	public synchronized void loadPrevPage() {
		if (myCurrentPageEntity != null) {
			if (myCurrentPageEntity.getPrevSubPageUrl() != null) {
				loadPageUrl(myCurrentPageEntity.getPrevSubPageUrl(), true);
			} else {
				if (myCurrentPageEntity.getPrevPageUrl() != null) {
					loadPageUrl(myCurrentPageEntity.getPrevPageUrl(), true);
				}
			}
		}
	}

	private boolean handleAboutDialog() {
		AlertDialog.Builder alert = new AlertDialog.Builder(this);
		alert.setTitle(getResources().getText(R.string.dialog_about_title));
		alert.setMessage(getResources().getText(R.string.dialog_about_message));
		TextView view = new TextView(this);
		view.setText(getResources().getText(R.string.dialog_about_text));
		alert.setView(view);
		alert.setPositiveButton(getResources().getText(R.string.dialog_about_ok),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
					}
				});
		alert.show();
		return true;
	}

	private boolean handleBackButton() {
		if (myHistoryStack.size() > 0) {
			loadPageUrl(myHistoryStack.pop().getPageUrl(), false);
		} else {
			isStopped = true;
			storePreferences();
			finish();
		}
		return true;
	}

	private boolean handleSetHomePage() {
		myHomePageUrl = myCurrentPageEntity.getPageUrl();
		Toast.makeText(getApplicationContext(), R.string.toast_homepageset, Toast.LENGTH_SHORT).show();
		return true;
	}

	private void initLocation() {
		// dummy default in NL
//		myLocation = new Location("AtoomTT");
//		myLocation.setLatitude(51.84247182857143d);
//		myLocation.setLongitude(5.862104228571428d);
//		myLocation.setTime(System.currentTimeMillis());

		LocationListener locationListener = new LocationListener() {
			public void onLocationChanged(Location location) {
				myLocation = location;
				Message msg = new Message();
				msg.arg1 = MESSAGE_UPDATE_ADVIEW;
				myHandler.sendMessageDelayed(msg, 100);
			}

			public void onStatusChanged(String provider, int status, Bundle extras) {
			}

			public void onProviderEnabled(String provider) {
			}

			public void onProviderDisabled(String provider) {
			}
		};

		LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
		locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 300000, 100f, locationListener);
	}

	private void initGraphics() {
		//requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().setTitle(getResources().getText(R.string.main_title));
		getWindow().setSoftInputMode(1);
                getWindow().requestFeature(Window.FEATURE_ACTION_BAR);
		setContentView(R.layout.main);
	}

	private void initMainWebViewAnimator() {
		myMainWebViewAnimator = new MainWebViewAnimator(this);
		FrameLayout frameLayout = (FrameLayout) findViewById(R.id.webview);
		frameLayout.addView(myMainWebViewAnimator);
	}

	private void initEditText() {

		myPageEditText = (EditText) findViewById(R.id.gotopageview);
		myPageEditText.setSelectAllOnFocus(true);
		myPageEditText.addTextChangedListener(new TextWatcher() {

			public void afterTextChanged(Editable s) {
				if (myPageEditText.getText().length() == 3) {

					String newPageId = myPageEditText.getText() + "";
					String currentPageId = "";
					if (myCurrentPageEntity != null && myCurrentPageEntity.getPageId() != null) {
						currentPageId = myCurrentPageEntity.getPageId();
						if (currentPageId.length() == 6) {
							currentPageId = currentPageId.substring(0, 3);
						}
					}

					if (currentPageId.equals(newPageId)) {
						if (LogBridge.isLoggable())
							LogBridge.i("Ignoring newPageId " + newPageId + " to prevent recursion");
					} else {
						String newPageUrl = "http://teletekst.nos.nl/tekst/" + newPageId + "-01.html";
						loadPageUrl(newPageUrl, true);
						myPageEditText.clearFocus();

						// close soft keyboard
						InputMethodManager inputManager = (InputMethodManager) TTActivity.this
								.getSystemService(Context.INPUT_METHOD_SERVICE);
						inputManager.hideSoftInputFromWindow(myPageEditText.getWindowToken(),
								InputMethodManager.HIDE_NOT_ALWAYS);

					}
				}
			}

			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

		});
	}

	private void initButtons() {

		myHomeButton = (Button) findViewById(R.id.homebuttonview);
		myHomeButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				loadPageUrl(myHomePageUrl, true);
			}
		});

		myNextPageButton = (Button) findViewById(R.id.nextpagebuttonview);
		myNextPageButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (!myCurrentPageEntity.getNextPageUrl().equals(""))
					loadPageUrl(myCurrentPageEntity.getNextPageUrl(), true);
			}
		});

		myNextSubPageButton = (Button) findViewById(R.id.nextsubbuttonview);
		myNextSubPageButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (!myCurrentPageEntity.getNextSubPageUrl().equals(""))
					loadPageUrl(myCurrentPageEntity.getNextSubPageUrl(), true);
			}
		});

		myPrevPageButton = (Button) findViewById(R.id.prevpagebuttonview);
		myPrevPageButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (!myCurrentPageEntity.getPrevPageUrl().equals(""))
					loadPageUrl(myCurrentPageEntity.getPrevPageUrl(), true);
			}
		});

		myPrevSubPageButton = (Button) findViewById(R.id.prevsubbuttonview);
		myPrevSubPageButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (!myCurrentPageEntity.getPrevSubPageUrl().equals(""))
					loadPageUrl(myCurrentPageEntity.getPrevSubPageUrl(), true);
			}
		});
	}

	private void loadPreferences() {
		SharedPreferences settings = getSharedPreferences(LOGGING_TAG, MODE_PRIVATE);
		if (settings != null) {
			myStartPageUrl = settings.getString(PREFS_CURRENT_URL, CONTENT_STARTPAGEURL);
			myHomePageUrl = settings.getString(PREFS_HOMEPAGE_URL, CONTENT_STARTPAGEURL);
		}
	}

	private void loadTemplate() {
		if (myTemplate == null || myTemplate.equals("")) {
			InputStream is = null;
			try {
				is = getAssets().open(TEMPLATE_FILENAME);
				InputStreamReader isr = new InputStreamReader(is);
				BufferedReader br = new BufferedReader(isr);
				StringBuilder sb = new StringBuilder(500);
				String line = null;
				while ((line = br.readLine()) != null) {
					sb.append(line);
					sb.append("\n");
				}
				myTemplate = sb.toString();
			} catch (IOException e) {
				if (is != null) {
					try {
						is.close();
					} catch (IOException e1) {
					}
				}
			}
		}
	}

	private void storePreferences() {
		SharedPreferences settings = getSharedPreferences(LOGGING_TAG, MODE_PRIVATE);
		if (settings != null) {
			SharedPreferences.Editor editor = settings.edit();
			if (editor != null) {
				editor.putString(PREFS_CURRENT_URL, myCurrentPageEntity.getPageUrl());
				editor.putString(PREFS_HOMEPAGE_URL, myHomePageUrl);
				editor.commit();
			}
		}
	}

	private void updateEditText() {
		myPageEditText.setText(myCurrentPageEntity.getPageId());
	}

	private void updateWebView() {
		String htmlData = myTemplate.replace(TEMPLATE_PLACEHOLDER, myCurrentPageEntity.getHtmlData());
		myMainWebViewAnimator.updateWebView(htmlData);
	}

	private void updateAdView() {
		AdView adView = (AdView) findViewById(R.id.ad);
		AdRequest adRequest = new AdRequest();
		adRequest.setLocation(myLocation);
		adView.loadAd(adRequest);
	}

	private void updateButtons() {
		if (myCurrentPageEntity.getPageUrl().equals(myHomePageUrl))
			disableButton(myHomeButton);
		else
			enableButton(myHomeButton);
		if (myCurrentPageEntity.getNextPageId().equals(""))
			disableButton(myNextPageButton);
		else
			enableButton(myNextPageButton);
		if (myCurrentPageEntity.getNextSubPageId().equals(""))
			disableButton(myNextSubPageButton);
		else
			enableButton(myNextSubPageButton);
		if (myCurrentPageEntity.getPrevPageId().equals(""))
			disableButton(myPrevPageButton);
		else
			enableButton(myPrevPageButton);
		if (myCurrentPageEntity.getPrevSubPageId().equals(""))
			disableButton(myPrevSubPageButton);
		else
			enableButton(myPrevSubPageButton);
	}

	private void enableButton(final Button button) {
		button.setEnabled(true);
		button.setFocusable(true);
	}

	private void disableButton(final Button button) {
		button.setEnabled(false);
		button.setFocusable(false);
	}
}
