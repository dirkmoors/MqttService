package com.qonect.protocols.mqtt.impl;

import com.qonect.protocols.mqtt.interfaces.IMqttMessage;

public class MqttMessage implements IMqttMessage {

	private byte[] payload;
	private int qos = 0;
	
	public MqttMessage(String content) {
		this(content.getBytes());
	}
	
	public MqttMessage(byte[] payload) {
		this.payload = payload;
	}

	@Override
	public int getQoS() {
		return qos;
	}

	@Override
	public byte[] getPayload() throws MqttException {
		return this.payload;
	}

	@Override
	public boolean isRetained() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isDuplicate() {
		// TODO Auto-generated method stub
		return false;
	}

	public void setQoS(int qos) {
		this.qos = qos;
	}
}
