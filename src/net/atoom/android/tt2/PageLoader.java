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

import net.atoom.android.tt2.util.LRUCache;
import net.atoom.android.tt2.util.LogBridge;

public final class PageLoader {

	private final static int CACHE_SIZE = 100;
	private final static long CACHE_TIME = 59000;

	private final LRUCache<String, PageEntity> myPageCache = new LRUCache<String, PageEntity>(
			CACHE_SIZE);
	private final HttpConnection myHttpConnection;
	private final PageProcessor myProcessor;

	public PageLoader() {
		myHttpConnection = new HttpConnection();
		myProcessor = new PageProcessor();
	}

	public synchronized PageEntity loadPage(final String pageUrl) {

		if(pageUrl == null || "".equals(pageUrl)){
			return null;
		}
		
		PageEntity pageEntity = myPageCache.get(pageUrl);
		if (pageEntity != null) {
			if ((System.currentTimeMillis() - CACHE_TIME) < pageEntity
					.getCreated()) {
				if (LogBridge.isLoggable())
					LogBridge.i("Returning cached entity: " + pageUrl);
				return pageEntity;
			}
			if (myHttpConnection.isPageModified(pageUrl, pageEntity.getETag())) {
				pageEntity.setCreated(System.currentTimeMillis());
				if (LogBridge.isLoggable())
					LogBridge.i("Returning unmodified entity: " + pageUrl);
				return pageEntity;
			}
			myPageCache.remove(pageUrl);
		}

		pageEntity = myHttpConnection.loadPage(pageUrl);
		if (pageEntity != null) {
			processPageEntity(pageEntity);
			myPageCache.put(pageUrl, pageEntity);
		}

		if (LogBridge.isLoggable())
			LogBridge.i("Returning new entity: " + pageUrl);
		return pageEntity;
	}

	private void processPageEntity(PageEntity pageEntity) {
		if (LogBridge.isLoggable())
			LogBridge.i("Processing new entity: " + pageEntity.getPageUrl());

		pageEntity.setPageId(myProcessor.pageIdFromUrl(pageEntity.getPageUrl()));
		
		pageEntity.setNextPageId(myProcessor.nextPageIdFromData(pageEntity
				.getHtmlData()));
		if (!pageEntity.getNextPageId().equals("")) {
			pageEntity.setNextPageUrl(myProcessor.pageUrlFromId(pageEntity
					.getNextPageId()));
		}

		pageEntity.setNextSubPageId(myProcessor
				.nextSubPageIdFromData(pageEntity.getHtmlData()));
		if (!pageEntity.getNextSubPageId().equals("")) {
			pageEntity.setNextSubPageUrl(myProcessor.pageUrlFromId(pageEntity
					.getNextSubPageId()));
		}

		pageEntity.setPrevPageId(myProcessor.prevPageIdFromData(pageEntity
				.getHtmlData()));
		if (!pageEntity.getPrevPageId().equals("")) {
			pageEntity.setPrevPageUrl(myProcessor.pageUrlFromId(pageEntity
					.getPrevPageId()));
		}

		pageEntity.setPrevSubPageId(myProcessor
				.prevSubPageIdFromData(pageEntity.getHtmlData()));
		if (!pageEntity.getPrevSubPageId().equals("")) {
			pageEntity.setPrevSubPageUrl(myProcessor.pageUrlFromId(pageEntity
					.getPrevSubPageId()));
		}

		pageEntity.setHtmlData(myProcessor.processRawPage(pageEntity
				.getHtmlData()));
	}
}
