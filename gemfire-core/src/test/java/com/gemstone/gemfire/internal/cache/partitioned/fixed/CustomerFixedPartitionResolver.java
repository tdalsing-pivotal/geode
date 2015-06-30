/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache.partitioned.fixed;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.util.Set;

import com.gemstone.gemfire.DataSerializable;
import com.gemstone.gemfire.cache.EntryOperation;
import com.gemstone.gemfire.cache.FixedPartitionResolver;
import com.gemstone.gemfire.internal.cache.execute.data.CustId;
import com.gemstone.gemfire.internal.cache.execute.data.OrderId;
import com.gemstone.gemfire.internal.cache.execute.data.ShipmentId;

public class CustomerFixedPartitionResolver implements FixedPartitionResolver, DataSerializable
     {

  public CustomerFixedPartitionResolver() {
  }

  public String getPartitionName(EntryOperation opDetails,
      Set allAvailablePartitions) {

    int customerID = -1;

    if (opDetails.getKey() instanceof ShipmentId) {
      ShipmentId shipmentId = (ShipmentId)opDetails.getKey();
      customerID = shipmentId.getOrderId().getCustId().getCustId();
    }
    if (opDetails.getKey() instanceof OrderId) {
      OrderId orderId = (OrderId)opDetails.getKey();
      customerID = orderId.getCustId().getCustId();
    }
    else if (opDetails.getKey() instanceof CustId) {
      CustId custId = (CustId)opDetails.getKey();
      customerID = custId.getCustId();
    }

    if (customerID >= 1 && customerID <= 10) {
      return "10";
    }
    else if (customerID > 10 && customerID <= 20) {
      return "20";
    }
    else if (customerID > 20 && customerID <= 30) {
      return "30";
    }
    else if (customerID > 30 && customerID <= 40) {
      return "40";
    }
    else {
      return "Invalid";
    }

  }

  public Serializable getRoutingObject(EntryOperation opDetails) {

    Serializable routingbject = null;

    if (opDetails.getKey() instanceof ShipmentId) {
      ShipmentId shipmentId = (ShipmentId)opDetails.getKey();
      routingbject = shipmentId.getOrderId().getCustId();
    }
    if (opDetails.getKey() instanceof OrderId) {
      OrderId orderId = (OrderId)opDetails.getKey();
      routingbject = orderId.getCustId();
    }
    else if (opDetails.getKey() instanceof CustId) {
      CustId custId = (CustId)opDetails.getKey();
      routingbject = custId.getCustId();
    }
    return routingbject;
  }


  public String getName() {
    return "CustomerFixedPartitionResolver";
  }

  public void close() {
    // TODO Auto-generated method stub
    
  }

  public void fromData(DataInput in) throws IOException, ClassNotFoundException {
    // TODO Auto-generated method stub
    
  }

  public void toData(DataOutput out) throws IOException {
    // TODO Auto-generated method stub
    
  }

}
