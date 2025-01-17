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
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import org.apache.geode.annotations.Immutable;
import org.apache.geode.cache.Region;
import org.apache.geode.internal.cache.BucketServerLocation66;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.tier.CachedRegionHelper;
import org.apache.geode.internal.cache.tier.Command;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.sockets.BaseCommand;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.cache.tier.sockets.ServerConnection;
import org.apache.geode.internal.security.SecurityService;

/**
 * {@link Command} for {@link GetClientPRMetadataCommand66}
 *
 *
 * @since GemFire 6.6
 */
public class GetClientPRMetadataCommand66 extends BaseCommand {

  @Immutable
  private static final GetClientPRMetadataCommand66 singleton = new GetClientPRMetadataCommand66();

  public static Command getCommand() {
    return singleton;
  }

  private GetClientPRMetadataCommand66() {}

  @Override
  public void cmdExecute(final @NotNull Message clientMessage,
      final @NotNull ServerConnection serverConnection,
      final @NotNull SecurityService securityService, long start)
      throws IOException, ClassNotFoundException, InterruptedException {
    String regionFullPath = null;
    CachedRegionHelper crHelper = serverConnection.getCachedRegionHelper();
    regionFullPath = clientMessage.getPart(0).getCachedString();
    String errMessage = "";
    if (regionFullPath == null) {
      logger.warn("The input region path for the GetClientPRMetadata request is null");
      errMessage =
          "The input region path for the GetClientPRMetadata request is null";
      writeErrorResponse(clientMessage, MessageType.GET_CLIENT_PR_METADATA_ERROR,
          errMessage.toString(), serverConnection);
      serverConnection.setAsTrue(RESPONDED);
    } else {
      Region region = crHelper.getRegion(regionFullPath);
      if (region == null) {
        logger.warn("Region was not found during GetClientPRMetadata request for region path : {}",
            regionFullPath);
        errMessage = "Region was not found during GetClientPRMetadata request for region path : "
            + regionFullPath;
        writeErrorResponse(clientMessage, MessageType.GET_CLIENT_PR_METADATA_ERROR,
            errMessage.toString(), serverConnection);
        serverConnection.setAsTrue(RESPONDED);
      } else {
        try {
          Message responseMsg = serverConnection.getResponseMessage();
          responseMsg.setTransactionId(clientMessage.getTransactionId());
          responseMsg.setMessageType(MessageType.RESPONSE_CLIENT_PR_METADATA);

          PartitionedRegion prRgion = (PartitionedRegion) region;
          Map<Integer, List<BucketServerLocation66>> bucketToServerLocations =
              prRgion.getRegionAdvisor().getAllClientBucketProfiles();
          responseMsg.setNumberOfParts(bucketToServerLocations.size());
          for (List<BucketServerLocation66> serverLocations : bucketToServerLocations.values()) {
            responseMsg.addObjPart(serverLocations);
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
  }

}
