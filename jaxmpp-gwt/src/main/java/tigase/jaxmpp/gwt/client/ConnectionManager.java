/*
 * Tigase XMPP Client Library
 * Copyright (C) 2006-2014 Tigase, Inc. <office@tigase.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
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
package tigase.jaxmpp.gwt.client;

import com.google.gwt.user.client.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;
import tigase.jaxmpp.core.client.BareJID;
import tigase.jaxmpp.core.client.Connector;
import tigase.jaxmpp.core.client.Connector.StateChangedHandler;
import tigase.jaxmpp.core.client.Context;
import tigase.jaxmpp.core.client.SessionObject;
import tigase.jaxmpp.core.client.connector.StreamError;
import tigase.jaxmpp.core.client.exceptions.JaxmppException;
import tigase.jaxmpp.core.client.xmpp.modules.ContextAware;
import tigase.jaxmpp.core.client.xmpp.modules.jingle.MutableBoolean;
import tigase.jaxmpp.gwt.client.connectors.BoshConnector;
import tigase.jaxmpp.gwt.client.connectors.SeeOtherHostHandler;
import tigase.jaxmpp.gwt.client.dns.WebDnsResolver;

/**
 *
 * @author andrzej
 */
public class ConnectionManager implements StateChangedHandler, SeeOtherHostHandler, ContextAware {

	private static final Logger log = Logger.getLogger(ConnectionManager.class.getName());
	
	private Jaxmpp jaxmpp;
	private Context context;
	private WebDnsResolver resolver = new WebDnsResolver();
	private WebDnsResolver.DomainResolutionCallback webDnsCallback = null;
	
	public ConnectionManager() {}
	
	@Override
	public void setContext(Context context) {
		this.context = context;
		resolver.setContext(context);
		context.getEventBus().addHandler(StateChangedHandler.StateChangedEvent.class, this);
		context.getEventBus().addHandler(SeeOtherHostHandler.SeeOtherHostEvent.class, this);
	}
	
//	public void setJaxmpp(Jaxmpp jaxmpp) {
//		this.jaxmpp = jaxmpp;
//	}
	
	public boolean initialize(Jaxmpp jaxmpp_) throws JaxmppException {
		if (this.jaxmpp == null) {
			this.jaxmpp = jaxmpp_;
		}
		if (this.webDnsCallback == null) {
			this.webDnsCallback = new WebDnsResolver.DomainResolutionCallback() {
				@Override
				public void onUrlResolved(String domain, String url) {
					try {
						jaxmpp.login(url);
					} catch (JaxmppException ex) {
						log.log(Level.SEVERE, "Exception while reconnecting after connection failure", ex);
						Connector.ErrorHandler.ErrorEvent e = new Connector.ErrorHandler.ErrorEvent(
								context.getSessionObject(), StreamError.undefined_condition, null);
						context.getEventBus().fire(e, this);
					}
				}

				@Override
				public void onUrlFailed() {
					Connector.ErrorHandler.ErrorEvent e = new Connector.ErrorHandler.ErrorEvent(
							context.getSessionObject(), StreamError.remote_connection_failed, null);
					context.getEventBus().fire(e, this);
				}
			};
		}
		
		if (context.getSessionObject().getProperty(BoshConnector.BOSH_SERVICE_URL_KEY) == null) {
			connectionFailure(null, false);
		}
		
		return context.getSessionObject().getProperty(BoshConnector.BOSH_SERVICE_URL_KEY) != null;
	}
	
	@Override
	public void onStateChanged(SessionObject sessionObject, Connector.State oldState, Connector.State newState) throws JaxmppException {
		if (oldState == newState) 
			return;
		
		String boshUrl = sessionObject.getProperty(BoshConnector.BOSH_SERVICE_URL_KEY);
		log.log(Level.INFO, "connector changed state " + oldState + " -> " + newState + " for = " + boshUrl);

		if (newState == Connector.State.disconnecting) {
			if (oldState == Connector.State.connecting) {
				log.log(Level.WARNING, "Disconnected before connecing");
				Timer timer = new Timer() {
					@Override
					public void run() {
						log.log(Level.SEVERE, "reconnecting");
						connectionFailure(null, true);
					}
				};
				timer.schedule(10);
			} else if (oldState == Connector.State.connected) {
				log.log(Level.WARNING, "Disconnected after being fully connected");
//				connectionFailure(null, false);
			}
		}
	}


	@Override
	public void onSeeOtherHost(String seeHost, MutableBoolean handled) {
		handled.setValue(true);
		connectionFailure(seeHost, false);
	}	
	
	public void connectionFailure(String seeHost, boolean hostFailure) {
		if (hostFailure) {
			String boshUrl = context.getSessionObject().getProperty(BoshConnector.BOSH_SERVICE_URL_KEY);
			resolver.hostFailure(boshUrl);
		}

		String domain = context.getSessionObject().getProperty(SessionObject.DOMAIN_NAME);
		if (domain == null) {
			BareJID userJid = context.getSessionObject().getUserBareJid();
			if (userJid != null) {
				domain = userJid.getDomain();
			}
		}
		resolver.getHostForDomain(domain, seeHost, webDnsCallback);
	}

}
