/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.spinnaker.oort.aws.provider.agent

import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeReservedInstancesRequest
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.OfferingTypeValues
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.netflix.amazoncomponents.security.AmazonClientProvider
import com.netflix.spinnaker.amos.aws.AmazonCredentials
import com.netflix.spinnaker.amos.aws.NetflixAmazonCredentials
import com.netflix.spinnaker.cats.agent.AgentDataType
import com.netflix.spinnaker.cats.agent.CacheResult
import com.netflix.spinnaker.cats.agent.CachingAgent
import com.netflix.spinnaker.cats.agent.DefaultCacheResult
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.oort.aws.model.AmazonReservationReport
import com.netflix.spinnaker.oort.aws.provider.AwsProvider
import groovy.util.logging.Slf4j
import org.codehaus.jackson.annotate.JsonCreator

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.oort.aws.data.Keys.Namespace.RESERVATION_REPORTS

@Slf4j
class ReservationReportCachingAgent implements CachingAgent {
  private static final Collection<AgentDataType> types = Collections.unmodifiableCollection([
    AUTHORITATIVE.forType(RESERVATION_REPORTS.ns)
  ])

  @Override
  String getProviderName() {
    AwsProvider.PROVIDER_NAME
  }

  @Override
  String getAgentType() {
    "${ReservationReportCachingAgent.simpleName}"
  }

  @Override
  Collection<AgentDataType> getProvidedDataTypes() {
    types
  }

  final AmazonClientProvider amazonClientProvider
  final Collection<NetflixAmazonCredentials> accounts
  final ObjectMapper objectMapper

  ReservationReportCachingAgent(AmazonClientProvider amazonClientProvider,
                                Collection<NetflixAmazonCredentials> accounts,
                                ObjectMapper objectMapper) {
    this.amazonClientProvider = amazonClientProvider
    this.accounts = accounts
    this.objectMapper = objectMapper.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
  }

  static class MutableCacheData implements CacheData {
    final String id
    int ttlSeconds = -1
    final Map<String, Object> attributes = [:]
    final Map<String, Collection<String>> relationships = [:].withDefault { [] as Set }

    public MutableCacheData(String id) {
      this.id = id
    }

    @JsonCreator
    public MutableCacheData(@JsonProperty("id") String id,
                            @JsonProperty("attributes") Map<String, Object> attributes,
                            @JsonProperty("relationships") Map<String, Collection<String>> relationships) {
      this(id);
      this.attributes.putAll(attributes);
      this.relationships.putAll(relationships);
    }
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    Map<String, AmazonReservationReport.ReservationDetail> reservations = [:].withDefault { String key ->
      def (availabilityZone, operatingSystemType, instanceType) = key.split(":")
      new AmazonReservationReport.ReservationDetail(
        availabilityZone: availabilityZone,
        os: AmazonReservationReport.OperatingSystemType.valueOf(operatingSystemType as String),
        instanceType: instanceType,
      )
    }

    def amazonReservationReport = new AmazonReservationReport(start: new Date())
    accounts.each { NetflixAmazonCredentials credentials ->
      credentials.regions.each { AmazonCredentials.AWSRegion region ->
        log.info("Fetching reservation report for ${credentials.name}:${region.name}")

        def amazonEC2 = amazonClientProvider.getAmazonEC2(credentials, region.name)
        def reservedInstancesResult = amazonEC2.describeReservedInstances(
          new DescribeReservedInstancesRequest()
            .withOfferingType(OfferingTypeValues.HeavyUtilization)
            .withFilters(new Filter().withName("state").withValues("active"))
        )

        reservedInstancesResult.reservedInstances.each {
          def productDescription = operatingSystemType(it.productDescription)
          def reservation = reservations["${it.availabilityZone}:${productDescription}:${it.instanceType}"]
          reservation.reserved.addAndGet(it.instanceCount)
        }

        def describeInstancesRequest = new DescribeInstancesRequest().withFilters(
          new Filter()
            .withName("instance-state-name")
            .withValues("pending", "running", "shutting-down", "stopping", "stopped")
        )

        while (true) {
          def result = amazonEC2.describeInstances(describeInstancesRequest)
          result.reservations.each {
            it.getInstances().each {
              def productDescription = operatingSystemType(it.platform ? "Windows" : "Linux/UNIX")
              def reservation = reservations["${it.placement.availabilityZone}:${productDescription}:${it.instanceType}"]
              reservation.used.incrementAndGet()
            }
          }

          if (result.nextToken) {
            describeInstancesRequest.withNextToken(result.nextToken)
          } else {
            break
          }
        }
      }
    }

    amazonReservationReport.end = new Date()
    amazonReservationReport.reservations = reservations.values()

    return new DefaultCacheResult(
      (RESERVATION_REPORTS.ns): [new MutableCacheData("latest", ["report": amazonReservationReport], [:])]
    )
  }

  static AmazonReservationReport.OperatingSystemType operatingSystemType(String productDescription) {
    switch (productDescription.toUpperCase()) {
      case "Linux/UNIX".toUpperCase():
        return AmazonReservationReport.OperatingSystemType.LINUX
      case "Linux/UNIX (Amazon VPC)".toUpperCase():
        return AmazonReservationReport.OperatingSystemType.LINUX
      case "Windows".toUpperCase():
        return AmazonReservationReport.OperatingSystemType.WINDOWS
      case "Windows (Amazon VPC)".toUpperCase():
        return AmazonReservationReport.OperatingSystemType.WINDOWS
      case "Red Hat Enterprise Linux".toUpperCase():
        return AmazonReservationReport.OperatingSystemType.RHEL
      default:
        log.error("Unknown product description (${productDescription})")
        return AmazonReservationReport.OperatingSystemType.UNKNOWN
    }
  }
}
