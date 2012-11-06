package com.qonect.protocols.mqtt.impl;

public class MqttException extends Throwable
{
	private static final long serialVersionUID = 9097334807440223499L;

	public MqttException()
	{
	}

	public MqttException(String detailMessage)
	{
		super(detailMessage);
	}

	public MqttException(Throwable throwable)
	{
		super(throwable);
	}

	public MqttException(String detailMessage, Throwable throwable)
	{
		super(detailMessage, throwable);
	}

}
