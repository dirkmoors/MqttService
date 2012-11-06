package com.qonect.protocols.mqtt.service;

import java.lang.ref.WeakReference;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings.Secure;
import android.util.Log;

import com.qonect.protocols.mqtt.impl.MqttConnectOptions;
import com.qonect.protocols.mqtt.impl.MqttException;
import com.qonect.protocols.mqtt.impl.MqttMessage;
import com.qonect.protocols.mqtt.impl.MqttPersistenceException;
import com.qonect.protocols.mqtt.impl.MqttTopic;
import com.qonect.protocols.mqtt.impl.paho.PahoMqttClientFactory;
import com.qonect.protocols.mqtt.interfaces.IMqttCallback;
import com.qonect.protocols.mqtt.interfaces.IMqttClient;
import com.qonect.protocols.mqtt.interfaces.IMqttClientFactory;
import com.qonect.protocols.mqtt.interfaces.IMqttConnectOptions;
import com.qonect.protocols.mqtt.interfaces.IMqttMessage;
import com.qonect.protocols.mqtt.interfaces.IMqttPersistence;
import com.qonect.protocols.mqtt.interfaces.IMqttTopic;

/*
 * An example of how to implement an MQTT client in Android, able to receive
 *  push notifications from an MQTT message broker server.
 *  
 *  Dale Lane (dale.lane@gmail.com)
 *    28 Jan 2011
 *    
 *  Modified into generic library and improved; Dirk Moors
 *    6 Nov 2012  
 */

public class MqttService extends Service implements IMqttCallback
{
    /************************************************************************/
    /*    CONSTANTS                                                         */
    /************************************************************************/
    
    // something unique to identify your app - used for stuff like accessing
    //   application preferences
    public static final String APP_ID = "com.qonect.services.mqtt";
    
    // constants used to notify the Activity UI of received messages    
    public static final String MQTT_MSG_RECEIVED_INTENT = "com.qonect.services.mqtt.MSGRECVD";
    public static final String MQTT_MSG_RECEIVED_TOPIC  = "com.qonect.services.mqtt.MSGRECVD_TOPIC";
    public static final String MQTT_MSG_RECEIVED_MSG    = "com.qonect.services.mqtt.MSGRECVD_MSG";
    
    // constants used to notify the Service of messages to send   
    public static final String MQTT_PUBLISH_MSG_INTENT = "com.qonect.services.mqtt.SENDMSG";
    public static final String MQTT_PUBLISH_MSG_TOPIC  = "com.qonect.services.mqtt.SENDMSG_TOPIC";
    public static final String MQTT_PUBLISH_MSG    = "com.qonect.services.mqtt.SENDMSG_MSG";
    
    // constants used to tell the Activity UI the connection status
    public static final String MQTT_STATUS_INTENT = "com.qonect.services.mqtt.STATUS";
    public static final String MQTT_STATUS_CODE    = "com.qonect.services.mqtt.STATUS_CODE";
    public static final String MQTT_STATUS_MSG    = "com.qonect.services.mqtt.STATUS_MSG";

    // constant used internally to schedule the next ping event
    public static final String MQTT_PING_ACTION = "com.qonect.services.mqtt.PING";
    
    // constants used by status bar notifications
    public static final int MQTT_NOTIFICATION_ONGOING = 1;  
    public static final int MQTT_NOTIFICATION_UPDATE  = 2;
    
    
    // constants used to define MQTT connection status
    public enum ConnectionStatus 
    {
        INITIAL,                            // initial status
        CONNECTING,                         // attempting to connect
        CONNECTED,                          // connected
        NOTCONNECTED_WAITINGFORINTERNET,    // can't connect because the phone
                                            //     does not have Internet access
        NOTCONNECTED_USERDISCONNECT,        // user has explicitly requested 
                                            //     disconnection
        NOTCONNECTED_DATADISABLED,          // can't connect because the user 
                                            //     has disabled data access
        NOTCONNECTED_UNKNOWNREASON          // failed to connect for some reason
    }

    // MQTT constants
    public static final int MAX_MQTT_CLIENTID_LENGTH = 22;

	private static final String TAG = "MQTTService";
    
    /************************************************************************/
    /*    VARIABLES used to maintain state                                  */
    /************************************************************************/
    
    // status of MQTT client connection
    private ConnectionStatus connectionStatus = ConnectionStatus.INITIAL;
    private Timestamp connectionStatusChangeTime;
    

    /************************************************************************/
    /*    VARIABLES used to configure MQTT connection                       */
    /************************************************************************/
    
    // taken from preferences
    //    host name of the server we're receiving push notifications from
    private String          brokerHostName       		 = "";
    // taken from preferences
    //    topic we want to receive messages about
    //    can include wildcards - e.g.  '#' matches anything
    private List<IMqttTopic>        topics            	 = new ArrayList<IMqttTopic>();    

    
    // defaults - this sample uses very basic defaults for it's interactions 
    //   with message brokers
    private int             		brokerPortNumber     = 1883;
    private IMqttPersistence 		usePersistence       = null;
    private boolean         		cleanStart           = false;
    private String 					username			 = "guest";
    private char[]					password			 = "guest".toCharArray();

    //  how often should the app ping the server to keep the connection alive?
    //
    //   too frequently - and you waste battery life
    //   too infrequently - and you wont notice if you lose your connection 
    //                       until the next unsuccessfull attempt to ping
    //
    //   it's a trade-off between how time-sensitive the data is that your 
    //      app is handling, vs the acceptable impact on battery life
    //
    //   it is perhaps also worth bearing in mind the network's support for 
    //     long running, idle connections. Ideally, to keep a connection open
    //     you want to use a keep alive value that is less than the period of
    //     time after which a network operator will kill an idle connection
    private short           keepAliveSeconds     = 20 * 60; 

    
    // This is how the Android client app will identify itself to the  
    //  message broker. 
    // It has to be unique to the broker - two clients are not permitted to  
    //  connect to the same broker using the same client ID. 
    private String          mqttClientId = null;     
    
    /************************************************************************/
    /*    VARIABLES  - other local variables                                */   
    /************************************************************************/
    // connection to the message broker
    private IMqttClient mqttClient = null;
    private IMqttClientFactory mqttClientFactory;
    
    // receiver that notifies the Service when an activity wants to publish to the mqtt
    private MessageSender messageSender;
    
    // receiver that notifies the Service when the phone gets data connection 
    private NetworkConnectionIntentReceiver netConnReceiver;
    
    // receiver that wakes the Service up when it's time to ping the server
    private PingSender pingSender;
    
    /************************************************************************/
    /*    METHODS - core Service lifecycle methods                          */
    /************************************************************************/
    // see http://developer.android.com/guide/topics/fundamentals.html#lcycles
    
    @Override
    public void onCreate() 
    {
        super.onCreate();
        
        // reset status variable to initial state
        changeStatus(ConnectionStatus.INITIAL);
        
        // create a binder that will let the Activity UI send 
        //   commands to the Service 
        mBinder = new LocalBinder<MqttService>(this);
        
        // get the broker settings out of app preferences
        //   this is not the only way to do this - for example, you could use 
        //   the Intent that starts the Service to pass on configuration values
        //SharedPreferences settings = getSharedPreferences(APP_ID, MODE_PRIVATE);
        brokerHostName = "profile-staging.jackzz.net";
        topics.add(new MqttTopic("test-topic"));
        
        mqttClientFactory = new PahoMqttClientFactory();  
    }
    
    
    @Override
    public void onStart(final Intent intent, final int startId) 
    {
        // This is the old onStart method that will be called on the pre-2.0
        // platform.  On 2.0 or later we override onStartCommand() so this
        // method will not be called. 
    	
    	Log.d(TAG, "onStart: intent="+intent+", startId="+startId);    	
    	doStart(intent, startId);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) 
    {
    	Log.d(TAG, "onStartCommand: intent="+intent+", flags="+flags+", startId="+startId);    	
    	doStart(intent, startId);
        
        // return START_NOT_STICKY - we want this Service to be left running 
        //  unless explicitly stopped, and it's process is killed, we want it to
        //  be restarted
        return START_STICKY;
    }    
    
    private void doStart(final Intent intent, final int startId){
    	initMqttClient();
    	
        new Thread(new Runnable() {
            @Override
            public void run() {
            	handleStart(intent, startId);
            }
        }, "Mqttservice").start();
    }    
    
    protected void onConnect(){
    	// we subscribe to a topic - registering to receive push
        //  notifications with a particular key
        // in a 'real' app, you might want to subscribe to multiple
        //  topics - I'm just subscribing to one as an example
        // note that this topicName could include a wildcard, so 
        //  even just with one subscription, we could receive 
        //  messages for multiple topics
        subscribeToTopics();
    }

    synchronized void handleStart(Intent intent, int startId) 
    {
        // before we start - check for a couple of reasons why we should stop
    	        
        if (mqttClient == null) 
        {
            // we were unable to define the MQTT client connection, so we stop 
            //  immediately - there is nothing that we can do
        	Log.d(TAG, "handleStart: mqttClient == null");
            stopSelf();
            return;
        }
        
        if (!isBackgroundDataEnabled()) // respect the user's request not to use data!
        {
            // user has disabled background data
        	changeStatus(ConnectionStatus.NOTCONNECTED_DATADISABLED);
            
            // update the app to show that the connection has been disabled
            broadcastServiceStatus("Not connected - background data disabled @ "+getConnectionChangeTimestamp());
            
            // we have a listener running that will notify us when this 
            //   preference changes, and will call handleStart again when it 
            //   is - letting us pick up where we leave off now 
            return;
        }
        
        // the Activity UI has started the MQTT service - this may be starting
        //  the Service new for the first time, or after the Service has been
        //  running for some time (multiple calls to startService don't start
        //  multiple Services, but it does call this method multiple times)
        // if we have been running already, we re-send any stored data        
        rebroadcastStatus();
        //rebroadcastReceivedMessages();
        
        // if the Service was already running and we're already connected - we 
        //   don't need to do anything 
        if (!isConnected()) 
        {
            // set the status to show we're trying to connect
        	changeStatus(ConnectionStatus.CONNECTING);
            
            // we are creating a background service that will run forever until
            //  the user explicity stops it. so - in case they start needing 
            //  to save battery life - we should ensure that they don't forget
            //  we're running, by leaving an ongoing notification in the status
            //  bar while we are running
            /*NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification notification = new Notification(R.drawable.ic_launcher, 
                                                         "MQTT",
                                                         System.currentTimeMillis());                                                         
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            Intent notificationIntent = new Intent(this, MQTTNotifier.class);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, 
                                                                    notificationIntent, 
                                                                    PendingIntent.FLAG_UPDATE_CURRENT);
            notification.setLatestEventInfo(this, "MQTT", "MQTT Service is running", contentIntent);        
            nm.notify(MQTT_NOTIFICATION_ONGOING, notification);*/
            Log.d(TAG, "handleStart: connectionStatus="+connectionStatus);
            
            
            // before we attempt to connect - we check if the phone has a 
            //  working data connection
            if (isOnline())
            {
                // we think we have an Internet connection, so try to connect
                //  to the message broker
                if (connectToBroker()) 
                {
                    onConnect();
                }
            }
            else
            {
                // we can't do anything now because we don't have a working 
                //  data connection 
            	changeStatus(ConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET);
                
                // inform the app that we are not connected
                broadcastServiceStatus("Waiting for network connection @ "+getConnectionChangeTimestamp());
            }
        }
        
        // changes to the phone's network - such as bouncing between WiFi 
        //  and mobile data networks - can break the MQTT connection
        // the MQTT connectionLost can be a bit slow to notice, so we use 
        //  Android's inbuilt notification system to be informed of 
        //  network changes - so we can reconnect immediately, without 
        //  haing to wait for the MQTT timeout
        if (netConnReceiver == null)
        {
            netConnReceiver = new NetworkConnectionIntentReceiver();            
            registerReceiver(netConnReceiver, 
            	new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));            
        }
        
        if(messageSender == null)
        {
        	messageSender = new MessageSender();
        	registerReceiver(messageSender,
        		new IntentFilter(MQTT_PUBLISH_MSG_INTENT));
        }
        
        // creates the intents that are used to wake up the phone when it is 
        //  time to ping the server
        if (pingSender == null)
        {
            pingSender = new PingSender();
            registerReceiver(pingSender, new IntentFilter(MQTT_PING_ACTION));
        }
    }

    @Override
    public void onDestroy() 
    {
        super.onDestroy();

        // disconnect immediately
        disconnectFromBroker();

        // inform the app that the app has successfully disconnected
        broadcastServiceStatus("Disconnected @ "+getConnectionChangeTimestamp());
        
        if (mBinder != null) {
            mBinder.close();
            mBinder = null;
        }
    }
    
    
    /************************************************************************/
    /*    METHODS - broadcasts and notifications                            */
    /************************************************************************/
    
    // methods used to notify the Activity UI of something that has happened
    //  so that it can be updated to reflect status and the data received 
    //  from the server
    
    private void broadcastServiceStatus(String statusDescription) 
    {
        // inform the app (for times when the Activity UI is running / 
        //   active) of the current MQTT connection status so that it 
        //   can update the UI accordingly
        
    	Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MQTT_STATUS_INTENT);
        broadcastIntent.putExtra(MQTT_STATUS_CODE, connectionStatus.ordinal());
        broadcastIntent.putExtra(MQTT_STATUS_MSG, statusDescription); 
        sendBroadcast(broadcastIntent);   
    }
    
    private void broadcastReceivedMessage(String topic, byte[] message)
    {
        // pass a message received from the MQTT server on to the Activity UI 
        //   (for times when it is running / active) so that it can be displayed 
        //   in the app GUI
       
    	Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MQTT_MSG_RECEIVED_INTENT);
        broadcastIntent.putExtra(MQTT_MSG_RECEIVED_TOPIC, topic);
        broadcastIntent.putExtra(MQTT_MSG_RECEIVED_MSG, message);
        sendBroadcast(broadcastIntent);      
    }
    
    // methods used to notify the user of what has happened for times when 
    //  the app Activity UI isn't running
    
    private void notifyUser(String alert, String title, String body)
    {
        /*NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new Notification(R.drawable.ic_launcher, alert,
                                                     System.currentTimeMillis());
        notification.defaults |= Notification.DEFAULT_LIGHTS;
        notification.defaults |= Notification.DEFAULT_SOUND;
        notification.defaults |= Notification.DEFAULT_VIBRATE;
        notification.flags |= Notification.FLAG_AUTO_CANCEL;        
        notification.ledARGB = Color.MAGENTA;
        Intent notificationIntent = new Intent(this, MQTTNotifier.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, 
                                                                notificationIntent, 
                                                                PendingIntent.FLAG_UPDATE_CURRENT);
        notification.setLatestEventInfo(this, title, body, contentIntent);
        nm.notify(MQTT_NOTIFICATION_UPDATE, notification);   */
    	Log.d(TAG, "notifyUser: alert="+alert+", title="+title+", body="+body);
    }
    
    
    /************************************************************************/
    /*    METHODS - binding that allows access from the Actitivy            */
    /************************************************************************/
    
    // trying to do local binding while minimizing leaks - code thanks to
    //   Geoff Bruckner - which I found at  
    //   http://groups.google.com/group/cw-android/browse_thread/thread/d026cfa71e48039b/c3b41c728fedd0e7?show_docid=c3b41c728fedd0e7
    
    private LocalBinder<MqttService> mBinder;
    
    @Override
    public IBinder onBind(Intent intent) 
    {
        return mBinder;
    }
    public class LocalBinder<S> extends Binder 
    {
        private WeakReference<S> mService;
        
        public LocalBinder(S service)
        {
            mService = new WeakReference<S>(service);
        }
        public S getService() 
        {
            return mService.get();
        }        
        public void close() 
        { 
            mService = null; 
        }
    }
    
    // 
    // public methods that can be used by Activities that bind to the Service
    //
    
    public ConnectionStatus getConnectionStatus() 
    {
        return connectionStatus;
    }    
    
    public void rebroadcastStatus()
    {
        String status = "";
        
        switch (connectionStatus)
        {
            case INITIAL:
                status = "Please wait";
                break;
            case CONNECTING:
                status = "Connecting @ "+getConnectionChangeTimestamp();
                break;
            case CONNECTED:
                status = "Connected @ "+getConnectionChangeTimestamp();
                break;
            case NOTCONNECTED_UNKNOWNREASON:
                status = "Not connected - waiting for network connection @ "+getConnectionChangeTimestamp();
                break;
            case NOTCONNECTED_USERDISCONNECT:
                status = "Disconnected @ "+getConnectionChangeTimestamp();
                break;
            case NOTCONNECTED_DATADISABLED:
                status = "Not connected - background data disabled @ "+getConnectionChangeTimestamp();
                break;
            case NOTCONNECTED_WAITINGFORINTERNET:
                status = "Unable to connect @ "+getConnectionChangeTimestamp();
                break;
        }
        
        
        // inform the app that the Service has successfully connected
        broadcastServiceStatus(status);
    }
    
    public void disconnect()
    {
        disconnectFromBroker();

        // set status 
        changeStatus(ConnectionStatus.NOTCONNECTED_USERDISCONNECT);
        
        // inform the app that the app has successfully disconnected
        broadcastServiceStatus("Disconnected");       
    }
    
    
    /************************************************************************/
    /*    METHODS - MQTT methods inherited from MQTT classes                */
    /************************************************************************/
    
    /*
     * callback - method called when we no longer have a connection to the 
     *  message broker server
     */
    public void connectionLost(Throwable t)
    {
        // we protect against the phone switching off while we're doing this
        //  by requesting a wake lock - we request the minimum possible wake 
        //  lock - just enough to keep the CPU running until we've finished
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();
        
        
        //
        // have we lost our data connection?
        //
        
        if (isOnline() == false)
        {
        	changeStatus(ConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET);
            
            // inform the app that we are not connected any more
            broadcastServiceStatus("Connection lost - no network connection");
        
            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we are no longer able to receive messages
            notifyUser("Connection lost - no network connection", 
                       "MQTT", "Connection lost - no network connection");

            //
            // wait until the phone has a network connection again, when we 
            //  the network connection receiver will fire, and attempt another
            //  connection to the broker
        }
        else 
        {
            // 
            // we are still online
            //   the most likely reason for this connectionLost is that we've 
            //   switched from wifi to cell, or vice versa
            //   so we try to reconnect immediately
            // 
            
        	changeStatus(ConnectionStatus.NOTCONNECTED_UNKNOWNREASON);
            
            // inform the app that we are not connected any more, and are 
            //   attempting to reconnect
            broadcastServiceStatus("Connection lost - reconnecting...");
            
            // try to reconnect
            //reconnectInNewThread();
        }
        
        // we're finished - if the phone is switched off, it's okay for the CPU 
        //  to sleep now
        wl.release();
    }

    
    /*
     *   callback - called when we receive a message from the server 
     */
    //public void publishArrived(String topic, byte[] payloadbytes, int qos, boolean retained)
    @Override
    public void messageArrived(IMqttTopic topic, IMqttMessage message)
		throws Exception
    {
        // we protect against the phone switching off while we're doing this
        //  by requesting a wake lock - we request the minimum possible wake 
        //  lock - just enough to keep the CPU running until we've finished
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();
        
        //
        //  I'm assuming that all messages I receive are being sent as strings
        //   this is not an MQTT thing - just me making as assumption about what
        //   data I will be receiving - your app doesn't have to send/receive
        //   strings - anything that can be sent as bytes is valid
        //String messageBody = new String(payloadbytes);
            
        // inform the app (for times when the Activity UI is running) of the 
        //   received message so the app UI can be updated with the new data
        try 
        {
			broadcastReceivedMessage(topic.getName(), message.getPayload());
		} 
        catch (MqttException e) 
		{
			e.printStackTrace();
		}        
 
        // receiving this message will have kept the connection alive for us, so
        //  we take advantage of this to postpone the next scheduled ping
        scheduleNextPing();

        // we're finished - if the phone is switched off, it's okay for the CPU 
        //  to sleep now        
        wl.release();
    }

    
    /************************************************************************/
    /*    METHODS - wrappers for some of the MQTT methods that we use       */
    /************************************************************************/
    
    /*
     * Create a client connection object that defines our connection to a
     *   message broker server 
     */
    private void initMqttClient()
    {
    	if(mqttClient != null){
    		return;
    	}
        
        try
        {        	
            // define the connection to the broker
            mqttClient = mqttClientFactory.create(brokerHostName, brokerPortNumber, getClientId(), usePersistence);//MqttClient.createMqttClient(mqttConnSpec, usePersistence);

            // register this client app has being able to receive messages
            //mqttClient.registerSimpleHandler(this);
            mqttClient.setCallback(this);
        }
        catch (MqttException e)
        {
            // something went wrong!
            mqttClient = null;
            changeStatus(ConnectionStatus.NOTCONNECTED_UNKNOWNREASON);
            
            //
            // inform the app that we failed to connect so that it can update
            //  the UI accordingly
            broadcastServiceStatus("Invalid connection parameters");

            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we failed to connect
            notifyUser("Unable to connect", "MQTT", "Unable to connect");
        }        
    }
    
    
    private void reconnectInNewThread(){
    	new Thread(new Runnable(){
			@Override
			public void run()
			{
				// we have an internet connection - have another try at connecting
                if (connectToBroker()) 
                {
                    // we subscribe to a topic - registering to receive push
                    //  notifications with a particular key
                    onConnect();
                }
			}
    		
    	}).start(); 
    }
    
    /*
     * (Re-)connect to the message broker
     */
    private boolean connectToBroker()
    {
        try
        {            
        	IMqttConnectOptions options = new MqttConnectOptions();
        	options.setCleanSession(cleanStart);
        	options.setKeepAliveInterval(keepAliveSeconds);
        	options.setUserName(username);
        	options.setPassword(password);
        	
            // try to connect
            mqttClient.connect(options); 
            
            // we are connected
            changeStatus(ConnectionStatus.CONNECTED);
            
            // inform the app that the app has successfully connected
            broadcastServiceStatus("Connected @ "+getConnectionChangeTimestamp());            

            // we need to wake up the phone's CPU frequently enough so that the 
            //  keep alive messages can be sent
            // we schedule the first one of these now
            scheduleNextPing();

            return true;
        }
        catch (MqttException e)
        {
            // something went wrong!            
        	changeStatus(ConnectionStatus.NOTCONNECTED_UNKNOWNREASON);
            
            //
            // inform the app that we failed to connect so that it can update
            //  the UI accordingly
            broadcastServiceStatus("Unable to connect @ "+getConnectionChangeTimestamp());

            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we failed to connect
            notifyUser("Unable to connect", "MQTT", "Unable to connect - will retry later");        
            
            // if something has failed, we wait for one keep-alive period before
            //   trying again
            // in a real implementation, you would probably want to keep count
            //  of how many times you attempt this, and stop trying after a 
            //  certain number, or length of time - rather than keep trying 
            //  forever.
            // a failure is often an intermittent network issue, however, so 
            //  some limited retry is a good idea
            scheduleNextPing();
            
            return false;
        }
    }
    
    /*
     * Send a request to the message broker to be sent messages published with 
     *  the specified topic names. Wildcards are allowed.    
     */
    private void subscribeToTopics()
    {
        boolean subscribed = false;
        
        if (!isConnected())
        {
            // quick sanity check - don't try and subscribe if we 
            //  don't have a connection            
            Log.e(TAG, "Unable to subscribe as we are not connected");
        }
        else 
        {                                    
            try 
            {
            	mqttClient.subscribe(
            		topics.toArray(new IMqttTopic[topics.size()]));
                
                subscribed = true;
            }             
            catch (IllegalArgumentException e) 
            {
                Log.e(TAG, "subscribe failed - illegal argument", e);
            } 
            catch (MqttException e) 
            {
                Log.e(TAG, "subscribe failed - MQTT exception", e);
            }
        }
        
        if (subscribed == false)
        {
            //
            // inform the app of the failure to subscribe so that the UI can 
            //  display an error
            broadcastServiceStatus("Unable to subscribe @ "+getConnectionChangeTimestamp());

            //
            // inform the user (for times when the Activity UI isn't running)
            notifyUser("Unable to subscribe", "MQTT", "Unable to subscribe");        
        }
    }
    
    /*
     * Terminates a connection to the message broker.
     */
    private void disconnectFromBroker()
    {
        // if we've been waiting for an Internet connection, this can be 
        //  cancelled - we don't need to be told when we're connected now
        try
        {
            if (netConnReceiver != null) 
            {
                unregisterReceiver(netConnReceiver);
                netConnReceiver = null;
            }
            
            if (pingSender != null)
            {
                unregisterReceiver(pingSender);
                pingSender = null;
            }
            
            if(messageSender != null)
            {
            	unregisterReceiver(messageSender);
            	messageSender = null;
            }
        }
        catch (Exception eee)
        {
            // probably because we hadn't registered it
            Log.e(TAG, "unregister failed", eee);
        }

        try 
        {
            if (mqttClient != null && mqttClient.isConnected())
            {
                mqttClient.disconnect();
            }
        } 
        catch (MqttPersistenceException e) 
        {
            Log.e(TAG, "disconnect failed - persistence exception", e);
        }
		catch (MqttException e)
		{
			Log.e(TAG, "disconnect failed - mqtt exception", e);
		}
        finally
        {
            mqttClient = null;
        }
        
        // we can now remove the ongoing notification that warns users that
        //  there was a long-running ongoing service running
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancelAll();
    }
    

    /*
     * Schedule the next time that you want the phone to wake up and ping the 
     *  message broker server
     */
    private void scheduleNextPing()
    {
        // When the phone is off, the CPU may be stopped. This means that our 
        //   code may stop running.
        // When connecting to the message broker, we specify a 'keep alive' 
        //   period - a period after which, if the client has not contacted
        //   the server, even if just with a ping, the connection is considered
        //   broken.
        // To make sure the CPU is woken at least once during each keep alive
        //   period, we schedule a wake up to manually ping the server
        //   thereby keeping the long-running connection open
        // Normally when using this Java MQTT client library, this ping would be
        //   handled for us. 
        // Note that this may be called multiple times before the next scheduled
        //   ping has fired. This is good - the previously scheduled one will be
        //   cancelled in favour of this one.
        // This means if something else happens during the keep alive period, 
        //   (e.g. we receive an MQTT message), then we start a new keep alive
        //   period, postponing the next ping.
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, 
                                                                 new Intent(MQTT_PING_ACTION), 
                                                                 PendingIntent.FLAG_UPDATE_CURRENT);
        
        // in case it takes us a little while to do this, we try and do it 
        //  shortly before the keep alive period expires
        // it means we're pinging slightly more frequently than necessary 
        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.SECOND, keepAliveSeconds);
        
        AlarmManager aMgr = (AlarmManager) getSystemService(ALARM_SERVICE);        
        aMgr.set(AlarmManager.RTC_WAKEUP,  
                 wakeUpTime.getTimeInMillis(),                 
                 pendingIntent);
    }

    /************************************************************************/
    /*    METHODS - internal utility methods                                */
    /************************************************************************/    
    
    private boolean isConnected()
    {
        return ((mqttClient != null) && (mqttClient.isConnected() == true));
    }
    
    @SuppressWarnings("deprecation")
	private boolean isBackgroundDataEnabled(){
    	ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
    	
    	//Only on pre-ICS platforms, backgroundDataSettings API exists
    	if(Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH){
    		return cm.getBackgroundDataSetting();
    	}
    	
    	//On ICS platform and higher, define BackgroundDataSetting by checking if
    	//phone is online
    	return isOnline();
    } 
    
    private boolean isOnline() 
    {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        
        return netInfo != null && netInfo.isAvailable() && netInfo.isConnected();
    }
    
    private String getClientId()
    {
        // generate a unique client id if we haven't done so before, otherwise
        //   re-use the one we already have

        if (mqttClientId == null)
        {
            String android_id = Secure.getString(getContentResolver(), 
                                                 Secure.ANDROID_ID);        
            mqttClientId = android_id;
            
            // truncate - MQTT spec doesn't allow client ids longer than 23 chars
            if (mqttClientId.length() > MAX_MQTT_CLIENTID_LENGTH) {
                mqttClientId = mqttClientId.substring(0, MAX_MQTT_CLIENTID_LENGTH);
            }
        }
        
        return mqttClientId;
    }
    
    private void changeStatus(ConnectionStatus newStatus){
    	connectionStatus = newStatus;
    	connectionStatusChangeTime = new Timestamp(new Date().getTime());
    }
    
    private String getConnectionChangeTimestamp(){
    	return connectionStatusChangeTime.toString();
    }
    
    public class MessageSender extends BroadcastReceiver  
	{
    	private static final String TAG = "MessageSender";
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d(TAG, "onReceive");
			
			if(!isOnline() || !isConnected()){
				Log.e(TAG, "onReceive: isOnline()="+isOnline()+", isConnected()="+isConnected());
				return;
			}
			
			byte[] payload = intent.getByteArrayExtra(MQTT_PUBLISH_MSG);
			
			try
			{
				mqttClient.publish(new MqttTopic("test-topic"), new MqttMessage(payload));
			}
			catch(MqttException e)
			{
				e.printStackTrace();
			}
		}
	}
    
    /*
     * Called in response to a change in network connection - after losing a 
     *  connection to the server, this allows us to wait until we have a usable
     *  data connection again
     */
    private class NetworkConnectionIntentReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context ctx, Intent intent) 
        {
            // we protect against the phone switching off while we're doing this
            //  by requesting a wake lock - we request the minimum possible wake 
            //  lock - just enough to keep the CPU running until we've finished
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
            wl.acquire();
            
            if (isOnline() && !isConnected())
            {
            	reconnectInNewThread();            	               
            }
            
            // we're finished - if the phone is switched off, it's okay for the CPU 
            //  to sleep now
            wl.release();
        }
    }
    
    /*
     * Used to implement a keep-alive protocol at this Service level - it sends 
     *  a PING message to the server, then schedules another ping after an 
     *  interval defined by keepAliveSeconds
     */
    public class PingSender extends BroadcastReceiver 
    {
        @Override
        public void onReceive(Context context, Intent intent) 
        {
            // Note that we don't need a wake lock for this method (even though
            //  it's important that the phone doesn't switch off while we're
            //  doing this).
            // According to the docs, "Alarm Manager holds a CPU wake lock as 
            //  long as the alarm receiver's onReceive() method is executing. 
            //  This guarantees that the phone will not sleep until you have 
            //  finished handling the broadcast."
            // This is good enough for our needs.
            
            try 
            {
                mqttClient.ping();         
            } 
            catch (MqttException e) 
            {
                // if something goes wrong, it should result in connectionLost
                //  being called, so we will handle it there
                Log.e("mqtt", "ping failed - MQTT exception", e);
                
                // assume the client connection is broken - trash it
                try {                    
                    mqttClient.disconnect();
                } 
                catch (MqttPersistenceException e1) {
                    Log.e("mqtt", "disconnect failed - persistence exception", e1);                
                }
				catch (MqttException e2)
				{					
					Log.e("mqtt", "disconnect failed - mqtt exception", e2);              
				}
                
                // reconnect
                reconnectInNewThread();               
            }

            // start the next keep alive period 
            scheduleNextPing();
        }
    }
}
