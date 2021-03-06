/*
 * BoshConnector.java
 *
 * Tigase XMPP Client Library
 * Copyright (C) 2004-2018 "Tigase, Inc." <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.jaxmpp.j2se.connectors.bosh;

import tigase.jaxmpp.core.client.Context;
import tigase.jaxmpp.core.client.connector.AbstractBoshConnector;
import tigase.jaxmpp.core.client.connector.BoshRequest;
import tigase.jaxmpp.core.client.exceptions.JaxmppException;
import tigase.jaxmpp.core.client.xml.Element;
import tigase.xml.DomBuilderHandler;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;

import java.net.URL;
import java.util.logging.Level;

public class BoshConnector
		extends AbstractBoshConnector {

	public static final String URL_KEY = "bosh#url";

	private final DomBuilderHandler domHandler = new DomBuilderHandler();

	private final SimpleParser parser = SingletonFactory.getParserInstance();

	public BoshConnector(Context context) {
		super(context);
	}

	@Override
	protected void processSendData(final Element element) throws JaxmppException {
		BoshRequest worker = new BoshWorker(domHandler, parser, context.getSessionObject(), element) {

			@Override
			protected void onError(int responseCode, String responseData, Element response, Throwable caught)
					throws JaxmppException {
				BoshConnector.this.onError(this, responseCode, responseData, response, caught);
			}

			@Override
			protected void onSuccess(int responseCode, String responseData, Element response) throws JaxmppException {
				BoshConnector.this.onResponse(this, responseCode, responseData, response);
			}

			@Override
			protected void onTerminate(int responseCode, String responseData, Element response) throws JaxmppException {
				BoshConnector.this.onTerminate(this, responseCode, responseData, response);
			}

		};

		addToRequests(worker);

		if (log.isLoggable(Level.FINEST)) {
			log.finest("Send: " + element.getAsString());
		}
		Thread t = new Thread(worker);
		t.setDaemon(true);
		t.start();
	}

	@Override
	public void start() throws JaxmppException {
		try {
			String u = context.getSessionObject().getProperty(AbstractBoshConnector.BOSH_SERVICE_URL_KEY);
			if (u == null) {
				throw new JaxmppException("BOSH service URL not defined!");
			}
			URL url = new URL(u);
			context.getSessionObject().setProperty(URL_KEY, url);
			super.start();
		} catch (JaxmppException e) {
			fireOnError(0, null, null, e, context.getSessionObject());
			throw e;
		} catch (Exception e) {
			fireOnError(0, null, null, e, context.getSessionObject());
			throw new JaxmppException(e);
		}
	}

}