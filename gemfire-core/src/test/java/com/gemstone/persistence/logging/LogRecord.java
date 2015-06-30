/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.persistence.logging;

/**
 * A <code>LogRecord</code> encapsulate an entry in a log.
 */
public class LogRecord {

  /** Global counter of sequence numbers */
  private static long nextSequenceNumber = 0;

  private Level level;
  private String loggerName;
  private String message;
  private long millis;
  private Object[] parameters;
  private long sequenceNumber;
  private String sourceClassName;
  private String sourceMethodName;
  private Throwable thrown;

  /**
   * Creates a new <code>LogRecord</code> with the given level and
   * message.
   */
  public LogRecord(Level level, String message) {
    this.level = level;
    this.message = message;
    this.sequenceNumber = nextSequenceNumber++;
    this.millis = System.currentTimeMillis();
  }

  /**
   * Sets the level at which the message id logged
   */
  public void setLevel(Level level) {
    this.level = level;
  }

  /**
   * Returns the level that the message should be logged at
   */
  public Level getLevel() {
    return(this.level);
  }

  /**
   * Sets the name of the logger to which this log record belongs
   */
  public void setLoggerName(String loggerName) {
    this.loggerName = loggerName;
  }

  /**
   * Returns the name of the logger to which this log record belongs
   */
  public String getLoggerName() {
    return(this.loggerName);
  }

  /**
   * Sets the message for this log entry
   */
  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * Returns the message for this log entry
   */
  public String getMessage() {
    return(this.message);
  }

  /**
   * Sets the event time
   */
  public void setMillis(long millis) {
    this.millis = millis;
  }

  /**
   * Returns the event time in milliseconds since 1970
   */
  public long getMillis() {
    return(this.millis);
  }

  /**
   * Sets the parameters to this log entry
   */
  public void setParameters(Object[] parameters) {
    this.parameters = parameters;
  }

  /**
   * Returns the parameters to this log entry
   */
  public Object[] getParameters() {
    return(this.parameters);
  }

  /**
   * Sets the sequence number of this log entry
   */
  public void setSequenceNumber(long sequenceNumber) {
    this.sequenceNumber = sequenceNumber;
  }

  /**
   * Returns the sequence number of this log entry
   */
  public long getSequenceNumber() {
    return(this.sequenceNumber);
  }

  /**
   * Sets the name of the source class from which this log entry was
   * issued
   */
  public void setSourceClassName(String sourceClassName) {
    this.sourceClassName = sourceClassName;
  }

  /**
   * Returns the name of the source class from which this log entry
   * was issued
   */
  public String getSourceClassName() {
    return(this.sourceClassName);
  }

  /**
   * Sets the name of the source method from which this log entry was
   * issued
   */
  public void setSourceMethodName(String sourceMethodName) {
    this.sourceMethodName = sourceMethodName;
  }

  /**
   * Returns the name of the source method from which this log entry
   * was issued
   */
  public String getSourceMethodName() {
    return(this.sourceMethodName);
  }

  /**
   * Sets the throwable associated with this log entry
   */
  public void setThrown(Throwable thrown) {
    this.thrown = thrown;
  }

  /**
   * Returns the throwable associated with this log entry
   */
  public Throwable getThrown() {
    return(this.thrown);
  }

  /**
   * Returns a brief textual description of this
   * <code>LogRecord</code>
   */
  public String toString() {
    return(this.message + " at " + this.level);
  }
}
