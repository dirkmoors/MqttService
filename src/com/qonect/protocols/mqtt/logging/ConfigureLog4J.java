package com.qonect.protocols.mqtt.logging;

import org.apache.log4j.Level;

import de.mindpipe.android.logging.log4j.LogConfigurator;

public class ConfigureLog4J {	
	
	public static final String PATTERN_CSV = "%d{HH:mm:ss},%c{1},%t,%p,%m%n";
	public static final String PATTERN_NORMAL1 = "%d{HH:mm:ss} - [%c{1}] - %p : %m%n";
	public static final String PATTERN_LOGCAT_WITH_THREAD = "[%t][%p]: %m%n";
	
    public static void configure(String logFileName, String filePattern) {
        final LogConfigurator logConfigurator = new LogConfigurator();
        
        logConfigurator.setFileName(logFileName);
        logConfigurator.setRootLevel(Level.ALL);
        
        logConfigurator.setImmediateFlush(true);
        
        logConfigurator.setFilePattern(filePattern);
        logConfigurator.setLogCatPattern(PATTERN_LOGCAT_WITH_THREAD);
        
        logConfigurator.configure();
    }
}