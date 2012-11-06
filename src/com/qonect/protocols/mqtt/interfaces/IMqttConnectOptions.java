package com.qonect.protocols.mqtt.interfaces;

public interface IMqttConnectOptions
{
	public void setCleanSession(boolean cleanStart);
	public void setKeepAliveInterval(short keepAliveSeconds);
	public void setUserName(String username);
	public void setPassword(char[] password);
	public boolean getCleanSession();	
	public int getKeepAliveInterval();
	public String getUserName();
	public char[] getPassword();
}
