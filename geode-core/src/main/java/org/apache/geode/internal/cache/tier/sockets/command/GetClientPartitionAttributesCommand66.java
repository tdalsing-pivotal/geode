/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.tier.sockets.command;

import java.io.IOException;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import org.apache.geode.annotations.Immutable;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.FixedPartitionAttributes;
import org.apache.geode.cache.PartitionResolver;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.internal.GetClientPartitionAttributesOp;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.PartitionedRegionHelper;
import org.apache.geode.internal.cache.tier.Command;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.sockets.BaseCommand;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.cache.tier.sockets.ServerConnection;
import org.apache.geode.internal.security.SecurityService;

/**
 * {@link Command} for {@link GetClientPartitionAttributesOp} operation for 6.6 clients
 *
 * @since GemFire 6.6
 */
public class GetClientPartitionAttributesCommand66 extends BaseCommand {

  @Immutable
  private static final GetClientPartitionAttributesCommand66 singleton =
      new GetClientPartitionAttributesCommand66();

  public static Command getCommand() {
    return singleton;
  }

  GetClientPartitionAttributesCommand66() {}

  @SuppressWarnings("unchecked")
  @Override
  public void cmdExecute(final @NotNull Message clientMessage,
      final @NotNull ServerConnection serverConnection,
      final @NotNull SecurityService securityService, long start)
      throws IOException, ClassNotFoundException, InterruptedException {
    String regionFullPath = null;
    regionFullPath = clientMessage.getPart(0).getCachedString();
    String errMessage = "";
    if (regionFullPath == null) {
      logger.warn("The input region path for the GetClientPartitionAttributes request is null");
      errMessage = "The input region path for the GetClientPartitionAttributes request is null";
      writeErrorResponse(clientMessage, MessageType.GET_CLIENT_PARTITION_ATTRIBUTES_ERROR,
          errMessage.toString(), serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }
    Region region = serverConnection.getCache().getRegion(regionFullPath);
    if (region == null) {
      logger.warn(
          "Region was not found during GetClientPartitionAttributes request for region path : {}",
          regionFullPath);
      errMessage =
          "Region was not found during GetClientPartitionAttributes request for region path : "
              + regionFullPath;
      writeErrorResponse(clientMessage, MessageType.GET_CLIENT_PARTITION_ATTRIBUTES_ERROR,
          errMessage.toString(), serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }

    try {
      Message responseMsg = serverConnection.getResponseMessage();
      responseMsg.setTransactionId(clientMessage.getTransactionId());
      responseMsg.setMessageType(MessageType.RESPONSE_CLIENT_PARTITION_ATTRIBUTES);

      if (!(region instanceof PartitionedRegion)) {
        responseMsg.setNumberOfParts(2);
        responseMsg.addObjPart(-1);
        responseMsg.addObjPart(region.getFullPath());
      } else {

        PartitionedRegion prRgion = (PartitionedRegion) region;

        PartitionResolver partitionResolver = prRgion.getPartitionResolver();
        int numParts = 2; // MINUMUM PARTS
        if (partitionResolver != null) {
          numParts++;
        }
        if (prRgion.isFixedPartitionedRegion()) {
          numParts++;
        }
        responseMsg.setNumberOfParts(numParts);
        // PART 1
        responseMsg.addObjPart(prRgion.getTotalNumberOfBuckets());

        // PART 2
        String leaderRegionPath = null;
        PartitionedRegion leaderRegion = null;
        String leaderRegionName = prRgion.getColocatedWith();
        if (leaderRegionName != null) {
          Cache cache = prRgion.getCache();
          while (leaderRegionName != null) {
            leaderRegion = (PartitionedRegion) cache.getRegion(leaderRegionName);
            if (leaderRegion.getColocatedWith() == null) {
              leaderRegionPath = leaderRegion.getFullPath();
              break;
            } else {
              leaderRegionName = leaderRegion.getColocatedWith();
            }
          }
        }
        responseMsg.addObjPart(leaderRegionPath);

        // PART 3
        if (partitionResolver != null) {
          responseMsg.addObjPart(partitionResolver.getClass().toString().substring(6));
        }
        // PART 4
        if (prRgion.isFixedPartitionedRegion()) {
          Set<FixedPartitionAttributes> fpaSet = null;
          if (leaderRegion != null) {
            fpaSet = PartitionedRegionHelper.getAllFixedPartitionAttributes(leaderRegion);
          } else {
            fpaSet = PartitionedRegionHelper.getAllFixedPartitionAttributes(prRgion);
          }
          responseMsg.addObjPart(fpaSet);
        }
      }
      responseMsg.send();
      clientMessage.clearParts();
    } catch (Exception e) {
      writeException(clientMessage, e, false, serverConnection);
    } finally {
      serverConnection.setAsTrue(Command.RESPONDED);
    }

  }
}
