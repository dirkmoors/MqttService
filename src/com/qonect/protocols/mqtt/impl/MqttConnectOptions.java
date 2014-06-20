package com.qonect.protocols.mqtt.impl;

import com.qonect.protocols.mqtt.interfaces.IMqttConnectOptions;

public class MqttConnectOptions implements IMqttConnectOptions
{
	//isCleanSession
	private boolean isClean ;
	
	@Override
	public void setCleanSession(boolean cleanStart)
	{
		isClean = cleanStart ;
		
	}

	@Override
	public void setKeepAliveInterval(short keepAliveSeconds)
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setUserName(String username)
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setPassword(char[] password)
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean getCleanSession()
	{
		
		return isClean;
	}

	@Override
	public int getKeepAliveInterval()
	{
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getUserName()
	{
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public char[] getPassword()
	{
		// TODO Auto-generated method stub
		return null;
	}
}
