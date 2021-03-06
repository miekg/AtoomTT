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

import net.atoom.android.tt2.util.LogBridge;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class MainWebViewClient extends WebViewClient {

	private final TTActivity myActivity;

	public MainWebViewClient(TTActivity activity) {
		myActivity = activity;
	}

	@Override
	public boolean shouldOverrideUrlLoading(WebView webView, String pageUrl) {
		if (LogBridge.isLoggable())
			LogBridge.i("Handling url: " + pageUrl);
		myActivity.loadPageUrl(pageUrl, true);
		return true;
	}
}