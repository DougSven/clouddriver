/*
 * Copyright 2015 Pivotal Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.kato.cf.deploy.converters

import com.netflix.spinnaker.clouddriver.cf.CloudFoundryOperation
import com.netflix.spinnaker.kato.cf.deploy.description.ResizeCloudFoundryServerGroupDescription
import com.netflix.spinnaker.kato.cf.deploy.ops.ResizeCloudFoundryServerGroupAtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperations
import com.netflix.spinnaker.kato.security.AbstractAtomicOperationsCredentialsSupport
import org.springframework.stereotype.Component

@CloudFoundryOperation(AtomicOperations.RESIZE_SERVER_GROUP)
@Component("resizeCloudFoundryServerGroupDescription")
class ResizeCloudFoundryServerGroupAtomicOperationConverter extends AbstractAtomicOperationsCredentialsSupport {

  @Override
  AtomicOperation convertOperation(Map input) {
    new ResizeCloudFoundryServerGroupAtomicOperation(convertDescription(input))
  }

  @Override
  Object convertDescription(Map input) {
    new ResizeCloudFoundryServerGroupDescription([
        serverGroupName : input.serverGroupName,
        zone            : input.containsKey('zones') ? input.zones[0] : input.containsKey('zone') ? input.zone : input.region,
        targetSize      : input.targetSize,
        credentials     : getCredentialsObject(input.credentials as String)
    ])
  }
}
