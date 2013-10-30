/**
 * 
 */
package com.atlassian.jira.ext.jabbernotifier.transport;

/**
 * @author �������
 * 
 */

import org.apache.log4j.Logger;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.jivesoftware.smack.packet.Presence.Type;

import java.util.HashMap;
import java.util.Map;

public class JabberTransport implements IMTransport
{

	private static final Logger log = Logger.getLogger(JabberTransport.class);

	private static Map<String, JabberTransport> transports = new HashMap<String, JabberTransport>();

	/**
	 * Factory method for creating JabberTransports. If a Transport with the
	 * specified connection parameters has already been created, it will be
	 * returned.
	 */
	public static synchronized IMTransport create(Map<String, String> params)
	{
		String key = params.get(XMPP_SERVER) + params.get(XMPP_LOGIN) + params.get(XMPP_PASSWORD)
				+ params.get(XMPP_PORT);
		if( ! transports.containsKey(key))
		{
			final JabberTransport transport = new JabberTransport();
			transport.setParams(params);
			transports.put(key, transport);
		}
		return transports.get(key);
	}

	private XMPPConnection xmppConnection;

	private static final String XMPP_SERVER = "XMPP Server";

	private static final String XMPP_LOGIN = "XMPP Login";

	private static final String XMPP_PASSWORD = "XMPP Password";

	private static final String XMPP_PORT = "XMPP Port (Default 5222)";

	private static final int DEFAULT_PORT = 5222;

	private String xmppServer;

	private String xmppLogin;

	private String xmppPassword;

	private int xmppPort;

	private TransportListener chatListener;

	private MessageListener aMsgListener;

	/**
	 * Create an unconfigured JabberTransport. Use
	 * {@link com.atlassian.jira.ext.jabbernotifier.transport.JabberTransport#create(java.util.Map)}
	 * to instantiate a properly initialized JabberTransport.
	 */
	public JabberTransport()
	{
		xmppPort = DEFAULT_PORT;
	}

	@Override
	public String[] getAcceptedParams()
	{
		return new String[]
		{
				XMPP_SERVER, XMPP_PORT, XMPP_LOGIN, XMPP_PASSWORD
		};
	}

	private void setParams(Map params)
	{
		if(params.containsKey(XMPP_SERVER))
		{
			xmppServer = (String) params.get(XMPP_SERVER);
		}
		if(params.containsKey(XMPP_LOGIN))
		{
			xmppLogin = (String) params.get(XMPP_LOGIN);
		}
		if(params.containsKey(XMPP_PASSWORD))
		{
			xmppPassword = (String) params.get(XMPP_PASSWORD);
		}
		if(params.containsKey(XMPP_PORT))
		{
			try
			{
				xmppPort = Integer.parseInt((String) params.get(XMPP_PORT));
			}
			catch(NumberFormatException e)
			{
				xmppPort = DEFAULT_PORT;
			}
		}
	}

	@Override
	public void connect() throws JabberServerConnectionException
	{
		if(isConnected())
			return;
		if((xmppServer != null && ! xmppServer.isEmpty())
				&& (xmppLogin != null && ! xmppLogin.isEmpty())
				&& (xmppPassword != null))
		{
			ConnectionConfiguration xmppConfiguration = new ConnectionConfiguration(
					xmppServer, xmppPort);
			xmppConfiguration.setReconnectionAllowed(true);
			xmppConfiguration.setSelfSignedCertificateEnabled(true);
			// disable automatic reconnection.
			// Firstly this plugin itself has a reconnection mechanism and
			// secondly the Smack ReconnectionManager is way too intense
			xmppConfiguration.setReconnectionAllowed(false);
			xmppConnection = new XMPPConnection(xmppConfiguration);
			try
			{
				xmppConnection.connect();
			}
			catch(XMPPException e)
			{
				throw new JabberServerConnectionException("Error connecting to Jabber server " + xmppServer + ":"
						+ xmppPort, e);
			}
		}
	}

	/**
	 * Log in to server. Must already be connected.
	 * 
	 * @throws JabberServerConnectionException
	 */
	private void authenticate() throws JabberServerConnectionException
	{
		if( ! isConnected())
			throw new JabberServerConnectionException("Not connected to Jabber server " + xmppServer + ":" + xmppPort);

		if(isAuthenticated())
			return;
		try
		{
			if(xmppLogin != null && ! xmppLogin.isEmpty() &&
					xmppPassword != null)
			{
				xmppConnection.login(xmppLogin, xmppPassword);
			}
		}
		catch(XMPPException e)
		{
			log.error("Error logging in to " + xmppServer + ":" + xmppPort + " : " + e.getMessage(), e);
		}
	}

	@Override
	public boolean isConnected()
	{
		Boolean result = false;
		if(xmppConnection != null)
		{
			result = xmppConnection.isConnected();
		}
		return result;
	}

	private boolean isAuthenticated()
	{
		Boolean result = false;
		if(isConnected())
		{
			result = xmppConnection.isAuthenticated();
		}
		return result;
	}

	@Override
	public synchronized void sendMessage(String toJID, String msg)
	{
		if(isConnected())
		{
			try
			{
				Roster roster = xmppConnection.getRoster();
				if( ! roster.contains(toJID))
				{
					roster.createEntry(toJID, toJID,
							new String[]
							{
								"NotificationRecipients"
							});
				}
				Chat chat = xmppConnection.getChatManager().createChat(toJID,
						null);
				chat.sendMessage(msg);
			}
			catch(XMPPException e)
			{
				log.error("Error sending XMPP message to " + toJID, e);
			}
		}
	}

	@Override
	public void setTransportListener(TransportListener listener)
	{
		chatListener = listener;
		if(isConnected())
		{
			aMsgListener = new MessageListener()
			{
				@Override
				public void processMessage(Chat chat, Message msg)
				{
					try
					{
						chat.sendMessage(chatListener.processMessage(
								cleanJID(chat.getParticipant()), msg.getBody()));
					}
					catch(XMPPException e)
					{
						log.error("Error sending message to " + chat.getParticipant(), e);
					}
				}
			};
			xmppConnection.getChatManager().addChatListener(
					new ChatManagerListener()
					{
						@Override
						public void chatCreated(Chat chat, boolean local)
						{
							if( ! local)
							{
								chat.addMessageListener(aMsgListener);
							}
						}
					});
		}
	}

	private String cleanJID(String jid)
	{
		String result = jid;
		if(jid.contains("/"))
		{
			result = jid.substring(0, jid.indexOf("/"));
		}
		return result;
	}

	@Override
	public IMStatus getContactStatus(String contact) throws JabberServerConnectionException
	{
		IMStatus result = IMStatus.OFFLINE;
		if( ! isConnected())
		{
			log.debug("Our jabber user " + xmppLogin + " went offline; reconnecting.");
			connect(); // reconnect if we went offline
		}
		if( ! isAuthenticated()) authenticate();

		if(isConnected() && isAuthenticated())
		{
			Roster roster = xmppConnection.getRoster();
			Presence presence = roster.getPresence(contact);
			log.debug("User " + contact + " is " + presence);
			if(presence.getType() == Type.available)
			{
				result = IMStatus.ONLINE;

				Mode status = presence.getMode();
				if(status == Mode.available || status == Mode.chat)
				{
					result = IMStatus.ONLINE;
				}
				else if(status == Mode.dnd)
				{
					result = IMStatus.BUSY;
				}
				else if(status == Mode.away)
				{
					result = IMStatus.AWAY;
				}
				else if(status == Mode.xa)
				{
					result = IMStatus.AWAY_LONG;
				}
			}
			else
			{
				RosterEntry entry = roster.getEntry(contact);
				if(entry == null)
					log.warn("Asked to notify " + contact + ", but this user is not on our roster (presence: "
							+ presence + ")");
				else
					log.debug("Not notifying " + contact + ", in status " + presence);
			}
		}
		else
			log.warn("Unable to connect to " + xmppServer + ":" + xmppPort + " and log in as " + xmppLogin);
		return result;
	}

	@Override
	public String getServer()
	{
		return xmppServer;
	}
}
