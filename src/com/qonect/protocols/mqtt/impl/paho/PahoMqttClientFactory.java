package com.qonect.protocols.mqtt.impl.paho;

import com.qonect.protocols.mqtt.impl.MqttException;
import com.qonect.protocols.mqtt.interfaces.IMqttClient;
import com.qonect.protocols.mqtt.interfaces.IMqttClientFactory;
import com.qonect.protocols.mqtt.interfaces.IMqttPersistence;

public class PahoMqttClientFactory implements IMqttClientFactory
{	
	@Override
	public IMqttClient create(String host, int port, String clientId,
		IMqttPersistence persistence) throws MqttException
	{
		PahoMqttClientPersistence persistenceImpl = null;
		if(persistence != null){
			persistenceImpl = new PahoMqttClientPersistence(persistence);
		}
		
		// TODO Auto-generated method stub
		return new PahoMqttClientWrapper(
			"tcp://"+host+":"+port, clientId, persistenceImpl);
	}
}
