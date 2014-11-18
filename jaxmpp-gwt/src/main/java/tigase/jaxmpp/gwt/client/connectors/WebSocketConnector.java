/*
 * Tigase XMPP Client Library
 * Copyright (C) 2013 "Andrzej Wójcik" <andrzej.wojcik@tigase.org>
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
package tigase.jaxmpp.gwt.client.connectors;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import tigase.jaxmpp.core.client.BareJID;
import tigase.jaxmpp.core.client.Connector;
import tigase.jaxmpp.core.client.Context;
import tigase.jaxmpp.core.client.PacketWriter;
import tigase.jaxmpp.core.client.SessionObject;
import tigase.jaxmpp.core.client.SessionObject.Scope;
import tigase.jaxmpp.core.client.XmppModulesManager;
import tigase.jaxmpp.core.client.XmppSessionLogic;
import tigase.jaxmpp.core.client.connector.AbstractBoshConnector;
import tigase.jaxmpp.core.client.connector.BoshXmppSessionLogic;
import tigase.jaxmpp.core.client.connector.StreamError;
import tigase.jaxmpp.core.client.exceptions.JaxmppException;
import tigase.jaxmpp.core.client.xml.Element;
import tigase.jaxmpp.core.client.xml.XMLException;
import tigase.jaxmpp.gwt.client.xml.GwtElement;

import com.google.gwt.user.client.Timer;
import com.google.gwt.xml.client.XMLParser;
import java.util.ArrayList;
import tigase.jaxmpp.core.client.xmpp.modules.StreamFeaturesModule;
import tigase.jaxmpp.core.client.xmpp.utils.MutableBoolean;

/**
 * 
 * @author andrzej
 */
public class WebSocketConnector implements Connector {

	public static final String FORCE_RFC_KEY = "websocket-force-rfc-mode";
	
	private final Context context;
	protected final Logger log;
	private Timer pingTimer = null;
	private WebSocket socket = null;

	private int SOCKET_TIMEOUT = 1000 * 60 * 3;
	private final WebSocketCallback socketCallback;
	
	private Boolean rfcCompatible = null;
	
	private boolean isRfc() {
		return rfcCompatible;
	}

	public WebSocketConnector(Context context) {
		this.log = Logger.getLogger(this.getClass().getName());
		this.context = context;

		socketCallback = new WebSocketCallback() {
			@Override
			public void onClose(WebSocket ws) {
				try {
					if (getState() == State.connected && !rfcCompatible 
							&& StreamFeaturesModule.getStreamFeatures(WebSocketConnector.this.context.getSessionObject()) == null) {
						if (pingTimer != null) {
							pingTimer.cancel();
							pingTimer = null;
						}
						rfcCompatible = true;
						start();
						return;
					}			
					if (getState() != State.disconnected && getState() == State.disconnecting) {
						if (pingTimer != null) {
							pingTimer.cancel();
							pingTimer = null;
						}
						fireOnError(null, null, WebSocketConnector.this.context.getSessionObject());
					} else {
						stop(true);
					}
				} catch (JaxmppException ex) {
					WebSocketConnector.this.onError(null, ex);
				}
			}

			@Override
			public void onError(WebSocket ws) {
				log.warning("received WebSocket error - terminating");
				try {
					if (pingTimer != null) {
						pingTimer.cancel();
						pingTimer = null;
					}
					fireOnError(null, null, WebSocketConnector.this.context.getSessionObject());
				} catch (JaxmppException ex) {
					WebSocketConnector.this.onError(null, ex);
				}
			}

			@Override
			public void onMessage(WebSocket ws, String message) {
				try {
					parseSocketData(message);
				} catch (JaxmppException ex) {
					WebSocketConnector.this.onError(null, ex);
				}
			}

			@Override
			public void onOpen(WebSocket ws) {
				try {
					if ("xmpp-framing".equals(ws.getProtocol())) {
						rfcCompatible = true;
					}
					setStage(State.connected);
					restartStream();

					pingTimer = new Timer() {
						@Override
						public void run() {
							try {
								keepalive();
							} catch (JaxmppException e) {
								log.log(Level.SEVERE, "Can't ping!", e);
							}
						}
					};
					int delay = SOCKET_TIMEOUT - 1000 * 5;

					if (log.isLoggable(Level.CONFIG)) {
						log.config("Whitespace ping period is setted to " + delay + "ms");
					}

					if (WebSocketConnector.this.context.getSessionObject().getProperty(EXTERNAL_KEEPALIVE_KEY) == null
							|| ((Boolean) WebSocketConnector.this.context.getSessionObject().getProperty(EXTERNAL_KEEPALIVE_KEY) == false)) {
						pingTimer.scheduleRepeating(delay);
					}

					fireOnConnected(WebSocketConnector.this.context.getSessionObject());

				} catch (JaxmppException ex) {
					WebSocketConnector.this.onError(null, ex);
				}
			}
		};
	}

	@Override
	public XmppSessionLogic createSessionLogic(XmppModulesManager modulesManager, PacketWriter writer) {
		return new WebSocketXmppSessionLogic(this, modulesManager, context);
	}

	protected void fireOnConnected(SessionObject sessionObject) throws JaxmppException {
		if (getState() == State.disconnected) {
			return;
		}
		context.getEventBus().fire(new ConnectedHandler.ConnectedEvent(sessionObject));
	}

	protected void fireOnError(Element response, Throwable caught, SessionObject sessionObject) throws JaxmppException {
		StreamError condition = null;

		if (response != null) {
			List<Element> es = response.getChildrenNS("urn:ietf:params:xml:ns:xmpp-streams");
			if (es != null) {
				for (Element element : es) {
					String n = element.getName();
					condition = StreamError.getByElementName(n);
				}
			}
		}

		context.getEventBus().fire(new ErrorHandler.ErrorEvent(sessionObject, condition, caught));
	}

	private void fireOnStanzaReceived(Element response, SessionObject sessionObject) throws JaxmppException {
		StanzaReceivedHandler.StanzaReceivedEvent event = new StanzaReceivedHandler.StanzaReceivedEvent(sessionObject, response);
		context.getEventBus().fire(event);
	}

	protected void fireOnTerminate(SessionObject sessionObject) throws JaxmppException {
		StreamTerminatedHandler.StreamTerminatedEvent event = new StreamTerminatedHandler.StreamTerminatedEvent(sessionObject);
		context.getEventBus().fire(event);
	}

	@Override
	public State getState() {
		return this.context.getSessionObject().getProperty(CONNECTOR_STAGE_KEY);
	}

	@Override
	public boolean isCompressed() {
		return false;
	}

	@Override
	public boolean isSecure() {
		return socket != null && socket.isSecure();
	}

	@Override
	public void keepalive() throws JaxmppException {
		if (context.getSessionObject().getProperty(DISABLE_KEEPALIVE_KEY) == Boolean.TRUE)
			return;
		if (getState() == State.connected)
			send(" ");
	}

	protected void onError(Element response, Throwable ex) {		
		try {
			if (response != null) {
				if (handleSeeOtherHost(response)) 
					return;
			}
			stop();
			fireOnError(null, ex, WebSocketConnector.this.context.getSessionObject());
		} catch (JaxmppException ex1) {
			log.log(Level.SEVERE, null, ex1);
		}
	}

	protected boolean handleSeeOtherHost(Element response) throws JaxmppException {
		if (response == null) 
			return false;

		Element seeOtherHost = response.getChildrenNS("see-other-host", "urn:ietf:params:xml:ns:xmpp-streams");
		if (seeOtherHost != null) {
			String seeHost = seeOtherHost.getValue();
			if (log.isLoggable(Level.FINE)) {
				log.fine("Received see-other-host=" + seeHost);
			}
			MutableBoolean handled = new MutableBoolean();
			context.getEventBus().fire(
					new SeeOtherHostHandler.SeeOtherHostEvent(context.getSessionObject(), seeHost, handled));

			return false;
		}
		return false;
	}		
	
	protected boolean handleSeeOtherUri(String seeOtherUri) throws JaxmppException {
		MutableBoolean handled = new MutableBoolean();
		context.getEventBus().fire(
				new SeeOtherHostHandler.SeeOtherHostEvent(context.getSessionObject(), seeOtherUri, handled));
		
		stop();
		fireOnError(null, null, WebSocketConnector.this.context.getSessionObject());		
		return false;
	}
	
	private void parseSocketData(String x) throws JaxmppException {
		// ignore keep alive "whitespace"
		if (x == null || x.length() == 1) {
			x = x.trim();
			if (x.length() == 0)
				return;
		}
		
		log.finest("received = " + x);

		// workarounds for xml parsers implemented in browsers
		if (x.endsWith("</stream:stream>") && !x.startsWith("<stream:stream ")) {
			x = "<stream:stream xmlns:stream='http://etherx.jabber.org/streams' >" + x;
		}
		// workarounds for xml parsers implemented in browsers
		else if (x.startsWith("<stream:")) {
			// unclosed xml tags causes error!!
			if (x.startsWith("<stream:stream ") && !x.contains("</stream:stream>")) {
				x += "</stream:stream>";
			}
			// xml namespace must be declared!!
			else if (!x.contains("xmlns:stream")) {
				int spaceIdx = x.indexOf(" ");
				int closeIdx = x.indexOf(">");
				int idx = spaceIdx < closeIdx ? spaceIdx : closeIdx;
				x = x.substring(0, idx) + " xmlns:stream='http://etherx.jabber.org/streams' " + x.substring(idx);
			}
		} else {
			x = "<root>" + x + "</root>";
		}

		Element response = new GwtElement(XMLParser.parse(x).getDocumentElement());
		List<Element> received = null;
		if ("stream:stream".equals(response.getName()) || "stream".equals(response.getName()) || "root".equals(response.getName())) {
			received = response.getChildren();
		}
		else if (response != null) {
			received = new ArrayList<Element>();
			received.add(response);
		}

		if (received != null) {
			boolean isRfc = isRfc();
			for (Element child : received) {
				if ("parsererror".equals(child.getName())) {
					continue;
				}

				if (isRfc && "urn:ietf:params:xml:ns:xmpp-framing".equals(child.getXMLNS())) {
					if ("close".equals(child.getName())) {
						if (child.getAttribute("see-other-uri") != null) {
						// received new version of see-other-host called see-other-uri 
							// designed just for XMPP over WebSocket
							String uri = child.getAttribute("see-other-uri");
							handleSeeOtherUri(uri);
							continue;
						}
						log.fine("received <close/> stanza, so we need to close this connection..");
						stop();
					}
					if ("open".equals(child.getName())) {
						// received <open/> stanza should be ignored
						continue;
					}
				}

				if (("error".equals(child.getName()) && child.getXMLNS() != null
						&& child.getXMLNS().equals("http://etherx.jabber.org/streams"))
						|| "stream:error".equals(child.getName())) {
					onError(child, null);
				} else {
					fireOnStanzaReceived(child, context.getSessionObject());
				}
			}
		}
		
		
	}

	@Override
	public void restartStream() throws XMLException, JaxmppException {
		StringBuilder sb = new StringBuilder();
		if (isRfc()) {
			sb.append("<open ");
		} else {
			sb.append("<stream:stream ");
		}

		final BareJID from = context.getSessionObject().getProperty(SessionObject.USER_BARE_JID);
		String to;
		Boolean seeOtherHost = context.getSessionObject().getProperty(SEE_OTHER_HOST_KEY);
		if (from != null && (seeOtherHost == null || seeOtherHost)) {
			to = from.getDomain();
			sb.append("from='").append(from.toString()).append("' ");
		} else {
			to = context.getSessionObject().getProperty(SessionObject.DOMAIN_NAME);
		}

		if (to != null) {
			sb.append("to='").append(to).append("' ");
		}

		sb.append("version='1.0' ");
		
		if (isRfc()) {
			sb.append("xmlns='urn:ietf:params:xml:ns:xmpp-framing'/>");
		} else {
			sb.append("xmlns='jabber:client' ");
			sb.append("xmlns:stream='http://etherx.jabber.org/streams'>");
		}

		if (log.isLoggable(Level.FINEST)) {
			log.finest("Restarting XMPP Stream");
		}
		send(sb.toString());
	}

	@Override
	public void send(Element stanza) throws XMLException, JaxmppException {
		if (stanza == null) {
			return;
		}
		send(stanza.getAsString());
	}

	public void send(final String data) throws JaxmppException {
		if (getState() == State.connected) {
			socket.send(data);
		} else {
			throw new JaxmppException("Not connected");
		}
	}

	protected void setStage(State state) throws JaxmppException {
		State s = this.context.getSessionObject().getProperty(CONNECTOR_STAGE_KEY);
		this.context.getSessionObject().setProperty(Scope.stream, CONNECTOR_STAGE_KEY, state);
		if (s != state) {
			log.fine("Connector state changed: " + s + "->" + state);
			StateChangedHandler.StateChangedEvent e = new StateChangedHandler.StateChangedEvent(context.getSessionObject(), s,
					state);
			context.getEventBus().fire(e);
			if (state == State.disconnected) {
				setStage(State.disconnected);
				fireOnTerminate(context.getSessionObject());
			}

			if (state == State.disconnecting) {
				try {
					throw new JaxmppException("disconnecting!!!");
				} catch (Exception ex) {
					log.log(Level.WARNING, "DISCONNECTING!!", ex);
				}
			}
		}
	}

	@Override
	public void start() throws XMLException, JaxmppException {
		if (rfcCompatible == null) {
			rfcCompatible = context.getSessionObject().getProperty(WebSocketConnector.FORCE_RFC_KEY);
		}
		if (rfcCompatible == null)
			rfcCompatible = false;
		String url = context.getSessionObject().getProperty(AbstractBoshConnector.BOSH_SERVICE_URL_KEY);
		setStage(State.connecting);
		// maybe we should add other "protocols" to indicate which version of xmpp-over-websocket is used?
		// if we would ask for "xmpp" and "xmpp-framing" new server would (at least Tigase) would respond
		// with "xmpp-framing" to try newer version of protocol and older with "xmpp" suggesting to try 
		// older protocol at first but in both cases we should be ready for error and failover!!
		// This would only reduce number of roundtrips in case of an error.
		// WARNING: old Tigase will not throw an exception when new protocol is tried - it will just hang.
		// Good idea would be also to allow user to pass protocol version (new, old, autodetection [old and if failed try the new one])
		socket = new WebSocket(url, new String[] { "xmpp", "xmpp-framing" }, socketCallback);
	}

	@Override
	public void stop() throws XMLException, JaxmppException {
		stop(false);
	}

	@Override
	public void stop(boolean terminate) throws XMLException, JaxmppException {
		if (getState() == State.disconnected) {
			return;
		}
		setStage(State.disconnecting);
		if (!terminate) {
			terminateStream();
		}

		if (this.pingTimer != null) {
			this.pingTimer.cancel();
			this.pingTimer = null;
		}

		socket.close();
		context.getEventBus().fire(new DisconnectedHandler.DisconnectedEvent(context.getSessionObject()));
	}

	private void terminateStream() throws JaxmppException {
		final State state = getState();
		if (state == State.connected || state == State.connecting) {
			String x = isRfc() ? "<close xmlns='urn:ietf:params:xml:ns:xmpp-framing'/>" : "</stream:stream>";
			log.fine("Terminating XMPP Stream");
			send(x);
		} else {
			log.fine("Stream terminate not sent, because of connection state==" + state);
		}
	}
}
