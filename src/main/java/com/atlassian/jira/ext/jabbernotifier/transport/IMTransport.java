package com.atlassian.jira.ext.jabbernotifier.transport;

public interface IMTransport 
{
	public enum IMStatus {OFFLINE, ONLINE, BUSY, AWAY, AWAY_LONG }
	
	public String[] getAcceptedParams();

	public void connect() throws JabberServerConnectionException;
	
	public boolean isConnected();
	
	public void sendMessage(String to, String msg) throws JabberServerConnectionException;
	
	public void setTransportListener(TransportListener listener);

    /**
     * Get online/offline status of Jabber user.
     * @param contact Non-null XMPP ID. The address could be in any valid format (e.g.
     *             "domain/resource", "user@domain" or "user@domain/resource"). Any resource
     *             information that's part of the ID will be discarded.
     * @return IM status of indicated user, or {@link com.atlassian.jira.ext.jabbernotifier.transport.IMTransport.IMStatus#OFFLINE if any connection or authentication errors occurred.
     * @throws JabberServerConnectionException 
     */
    public IMStatus getContactStatus(String contact) throws JabberServerConnectionException;

	public String getServer();
}