/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may
 * not use this file except in compliance with the License. A copy of the
 * License is located at
 *
 *    http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.flink.refarch;

import ch.hsr.geohash.GeoHash;
import com.amazonaws.flink.refarch.events.EventSchema;
import com.amazonaws.flink.refarch.events.PunctuatedAssigner;
import com.amazonaws.flink.refarch.events.es.PickupCount;
import com.amazonaws.flink.refarch.events.es.TripDuration;
import com.amazonaws.flink.refarch.events.kinesis.Event;
import com.amazonaws.flink.refarch.events.kinesis.TripEvent;
import com.amazonaws.flink.refarch.utils.ElasticsearchJestSink;
import com.amazonaws.flink.refarch.utils.GeoUtils;
import com.amazonaws.services.kinesisanalytics.flink.connectors.producer.FlinkKinesisFirehoseProducer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.CheckpointConfig.ExternalizedCheckpointCleanup;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.util.Collector;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Properties;
import java.util.stream.StreamSupport;


public class ProcessTaxiStream {
  private static final String DEFAULT_REGION = "eu-east-1";

  private static final int MIN_PICKUP_COUNT = 2;
  private static final int GEOHASH_PRECISION = 6;
  private static final String ES_DEFAULT_INDEX = "taxi-dashboard";

  private static final Logger LOG = LoggerFactory.getLogger(ProcessTaxiStream.class);


  private static FlinkKinesisFirehoseProducer<String> createFirehoseSinkFromStaticConfig(final String firehoseDeliveryStream,
                                                                                         final String awsRegion) {
    /*
     * com.amazonaws.services.kinesisanalytics.flink.connectors.config.ProducerConfigConstants
     * lists of all of the properties that firehose sink can be configured with.
     */

    Properties outputProperties = new Properties();
    outputProperties.setProperty(ConsumerConfigConstants.AWS_REGION, awsRegion);
    String outputDeliveryStreamName = firehoseDeliveryStream;
    FlinkKinesisFirehoseProducer<String> sink =
            new FlinkKinesisFirehoseProducer<>(outputDeliveryStreamName, new SimpleStringSchema(), outputProperties);
    return sink;
  }
  public static void main(String[] args) throws Exception {
    ParameterTool pt = ParameterTool.fromArgs(args);

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    if (! pt.has("noeventtime")) {
      env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
    }

    Properties kinesisConsumerConfig = new Properties();
    kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_REGION, pt.get("region", DEFAULT_REGION));
    kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");
    kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_MAX, "10000");
    kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS, "2000");


    DataStream<Event> kinesisStream = env.addSource(new FlinkKinesisConsumer<>(
        pt.getRequired("stream"),
        new EventSchema(),
        kinesisConsumerConfig)
    );

    DataStream<TripEvent> trips = kinesisStream
        .rebalance()
        .assignTimestampsAndWatermarks(new PunctuatedAssigner())
        .filter(event -> TripEvent.class.isAssignableFrom(event.getClass()))
        .map(event -> (TripEvent) event)
        .filter(GeoUtils::hasValidCoordinates)
        .filter(GeoUtils::nearNYC);

    DataStream<PickupCount> pickupCounts = trips
        .map(trip -> new Tuple1<>(GeoHash.geoHashStringWithCharacterPrecision(trip.pickupLat, trip.pickupLon, GEOHASH_PRECISION)))
        .keyBy(0)
        .timeWindow(Time.minutes(10))
        .apply((Tuple tuple, TimeWindow window, Iterable<Tuple1<String>> input, Collector<PickupCount> out) -> {
          long count = Iterables.size(input);
          String position = Iterables.get(input, 0).f0;

          out.collect(new PickupCount(position, count, window.maxTimestamp()));
        })
        .filter(geo -> geo.pickupCount >= MIN_PICKUP_COUNT);


    DataStream<TripDuration> tripDurations = trips
        .flatMap((TripEvent trip, Collector<Tuple3<String, String, Long>> out) -> {
          String pickupLocation = GeoHash.geoHashStringWithCharacterPrecision(trip.pickupLat, trip.pickupLon, GEOHASH_PRECISION);
          long tripDuration = new Duration(trip.pickupDatetime, trip.dropoffDatetime).getStandardMinutes();

          if (GeoUtils.nearJFK(trip.dropoffLat, trip.dropoffLon)) {
            out.collect(new Tuple3<>(pickupLocation, "JFK", tripDuration));
          } else if (GeoUtils.nearLGA(trip.dropoffLat, trip.dropoffLon)) {
            out.collect(new Tuple3<>(pickupLocation, "LGA", tripDuration));
          }
        })
        .keyBy(0,1)
        .timeWindow(Time.minutes(10))
        .apply((Tuple tuple, TimeWindow window, Iterable<Tuple3<String,String,Long>> input, Collector<TripDuration> out) -> {
          if (Iterables.size(input) > 1) {
            String location = Iterables.get(input, 0).f0;
            String airportCode = Iterables.get(input, 0).f1;

            long sumDuration = StreamSupport
                .stream(input.spliterator(), false)
                .mapToLong(trip -> trip.f2)
                .sum();

            double avgDuration = (double) sumDuration / Iterables.size(input);

            out.collect(new TripDuration(location, airportCode, sumDuration, avgDuration, window.maxTimestamp()));
          }
        });

    String outputDeliveryStreamName = pt.get("outputDeliveryStreamName");
    String outputDeliveryStreamRegion = pt.get("outputDeliveryStreamRegion");
    pickupCounts.map(PickupCount::toString).addSink(createFirehoseSinkFromStaticConfig(outputDeliveryStreamName,
            outputDeliveryStreamRegion));

    LOG.info("Starting to consume events from stream {}", pt.getRequired("stream"));

    env.execute();
  }
}
