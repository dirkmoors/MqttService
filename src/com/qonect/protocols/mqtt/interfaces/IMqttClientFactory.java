package com.qonect.protocols.mqtt.interfaces;

import com.qonect.protocols.mqtt.impl.MqttException;

public interface IMqttClientFactory
{
	public IMqttClient create(String host, int port, String clientId, IMqttPersistence persistence) throws MqttException;
}
