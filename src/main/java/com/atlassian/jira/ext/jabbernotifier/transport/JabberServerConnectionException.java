package com.atlassian.jira.ext.jabbernotifier.transport;


import org.jivesoftware.smack.XMPPException;

public class JabberServerConnectionException extends Exception
{
	public JabberServerConnectionException(String message, XMPPException e)
	{
		super(message, e);
	}

	public JabberServerConnectionException(String message)
	{
		super(message);
	}
}
