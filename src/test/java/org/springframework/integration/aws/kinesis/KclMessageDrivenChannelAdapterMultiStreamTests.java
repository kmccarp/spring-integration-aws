/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aws.kinesis;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.Consumer;
import software.amazon.kinesis.common.InitialPositionInStream;
import software.amazon.kinesis.common.InitialPositionInStreamExtended;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.aws.LocalstackContainerTest;
import org.springframework.integration.aws.inbound.kinesis.KclMessageDrivenChannelAdapter;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.messaging.Message;
import org.springframework.messaging.PollableChannel;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Siddharth Jain
 * @author Artem Bilan
 *
 * @since 3.0
 */
@SpringJUnitConfig
@DirtiesContext

public class KclMessageDrivenChannelAdapterMultiStreamTests implements LocalstackContainerTest {

	private static final String TEST_STREAM1 = "MultiStreamKcl1";

	private static final String TEST_STREAM2 = "MultiStreamKcl2";

	private static KinesisAsyncClient amazonKinesis;

	private static DynamoDbAsyncClient dynamoDb;

	private static CloudWatchAsyncClient cloudWatch;

	@Autowired
	private PollableChannel kinesisReceiveChannel;

	@BeforeAll
	static void setup() {
		amazonKinesis = LocalstackContainerTest.kinesisClient();
		dynamoDb = LocalstackContainerTest.dynamoDbClient();
		cloudWatch = LocalstackContainerTest.cloudWatchClient();

		CompletableFuture<?> completableFuture1 =
				amazonKinesis.createStream(request -> request.streamName(TEST_STREAM1).shardCount(1))
						.thenCompose(result -> amazonKinesis.waiter()
								.waitUntilStreamExists(request -> request.streamName(TEST_STREAM1)));

		CompletableFuture<?> completableFuture2 =
				amazonKinesis.createStream(request -> request.streamName(TEST_STREAM2).shardCount(1))
						.thenCompose(result -> amazonKinesis.waiter()
								.waitUntilStreamExists(request -> request.streamName(TEST_STREAM2)));

		CompletableFuture.allOf(completableFuture1, completableFuture2).join();
	}

	@AfterAll
	static void tearDown() {
		CompletableFuture<?> completableFuture1 =
				amazonKinesis.deleteStream(request -> request.streamName(TEST_STREAM1).enforceConsumerDeletion(true))
						.thenCompose(result -> amazonKinesis.waiter()
								.waitUntilStreamNotExists(request -> request.streamName(TEST_STREAM1)));

		CompletableFuture<?> completableFuture2 =
				amazonKinesis.deleteStream(request -> request.streamName(TEST_STREAM2).enforceConsumerDeletion(true))
						.thenCompose(result -> amazonKinesis.waiter()
								.waitUntilStreamNotExists(request -> request.streamName(TEST_STREAM2)));

		CompletableFuture.allOf(completableFuture1, completableFuture2).join();
	}

	@Test
	public void kclChannelAdapterMultiStream() {
		String testData = "test data";
		amazonKinesis.putRecord(request -> request
				.streamName(TEST_STREAM1)
				.data(SdkBytes.fromUtf8String(testData))
				.partitionKey("test"));

		String testData2 = "test data 2";
		amazonKinesis.putRecord(request -> request
				.streamName(TEST_STREAM2)
				.data(SdkBytes.fromUtf8String(testData2))
				.partitionKey("test"));

		// The below statement works but with a higher timeout. For 2 streams, this takes too long.
		Message<?> receive = this.kinesisReceiveChannel.receive(300_000);
		assertThat(receive).isNotNull();
		assertThat(receive.getPayload()).isEqualTo(testData);
		assertThat(receive.getHeaders().get(AwsHeaders.RECEIVED_SEQUENCE_NUMBER, String.class)).isNotEmpty();

		receive = this.kinesisReceiveChannel.receive(10_000);
		assertThat(receive).isNotNull();
		assertThat(receive.getPayload()).isEqualTo(testData2);

		List<Consumer> stream1Consumers =
				amazonKinesis.describeStream(request -> request.streamName(TEST_STREAM1))
						.thenCompose(describeStreamResponse ->
								amazonKinesis.listStreamConsumers(request ->
										request.streamARN(describeStreamResponse.streamDescription().streamARN())))
						.join()
						.consumers();

		List<Consumer> stream2Consumers = amazonKinesis
				.describeStream(request -> request.streamName(TEST_STREAM2))
				.thenCompose(describeStreamResponse ->
						amazonKinesis.listStreamConsumers(request ->
								request.streamARN(describeStreamResponse.streamDescription().streamARN())))
				.join()
				.consumers();

		assertThat(stream1Consumers).hasSize(1);
		assertThat(stream2Consumers).hasSize(1);
	}

	@Configuration
	@EnableIntegration
	public static class TestConfiguration {

		@Bean
		public KclMessageDrivenChannelAdapter kclMessageDrivenChannelAdapter() {
			KclMessageDrivenChannelAdapter adapter = new KclMessageDrivenChannelAdapter(
					amazonKinesis, cloudWatch, dynamoDb, TEST_STREAM1, TEST_STREAM2);
			adapter.setOutputChannel(kinesisReceiveChannel());
			adapter.setStreamInitialSequence(
					InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON));
			adapter.setConverter(String::new);
			adapter.setConsumerGroup("multi_stream_group");
			return adapter;
		}

		@Bean
		public PollableChannel kinesisReceiveChannel() {
			return new QueueChannel();
		}

	}

}
