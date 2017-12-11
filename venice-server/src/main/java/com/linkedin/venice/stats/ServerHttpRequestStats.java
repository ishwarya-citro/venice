package com.linkedin.venice.stats;

import com.linkedin.venice.read.RequestType;
import io.tehuti.metrics.MeasurableStat;
import io.tehuti.metrics.MetricsRepository;
import io.tehuti.metrics.Sensor;
import io.tehuti.metrics.stats.Avg;
import io.tehuti.metrics.stats.Max;
import io.tehuti.metrics.stats.OccurrenceRate;
import io.tehuti.metrics.stats.Rate;
import io.tehuti.metrics.stats.SampledCount;
import io.tehuti.metrics.stats.SampledTotal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class ServerHttpRequestStats extends AbstractVeniceHttpStats{
  private final Sensor successRequestSensor;
  private final Sensor errorRequestSensor;
  private final Sensor successRequestLatencySensor;
  private final Sensor errorRequestLatencySensor;
  private final Sensor bdbQueryLatencySensor;
  private final Sensor bdbQueryLatencyForSmallValueSensor;
  private final Sensor bdbQueryLatencyForLargeValueSensor;
  private final Sensor multiChunkLargeValueCountSensor;
  private final Sensor requestKeyCountSensor;
  private final Sensor successRequestKeyCountSensor;

  // Ratio sensors are not directly written to, but they still get their state updated indirectly
  @SuppressWarnings("unused")
  private final Sensor successRequestKeyRatioSensor, successRequestRatioSensor;


  public ServerHttpRequestStats(MetricsRepository metricsRepository,
                                String storeName, RequestType requestType) {
    super(metricsRepository, storeName, requestType);

    /**
     * Check java doc of function: {@link TehutiUtils.RatioStat} to understand why choosing {@link Rate} instead of
     * {@link io.tehuti.metrics.stats.SampledStat}.
     */
    Rate successRequest = new OccurrenceRate();
    Rate errorRequest = new OccurrenceRate();
    successRequestSensor = registerSensor("success_request", successRequest);
    errorRequestSensor = registerSensor("error_request", errorRequest);
    successRequestLatencySensor = registerSensor("success_request_latency",
        TehutiUtils.getPercentileStat(getName(), getFullMetricName("success_request_latency")));
    errorRequestLatencySensor = registerSensor("error_request_latency",
        TehutiUtils.getPercentileStat(getName(), getFullMetricName("error_request_latency")));
    successRequestRatioSensor = registerSensor("success_request_ratio",
        new TehutiUtils.RatioStat(successRequest, errorRequest));

    bdbQueryLatencySensor = registerSensor("storage_engine_query_latency",
        TehutiUtils.getPercentileStat(getName(), getFullMetricName("storage_engine_query_latency")));
    bdbQueryLatencyForSmallValueSensor = registerSensor("storage_engine_query_latency_for_small_value",
        TehutiUtils.getPercentileStat(getName(), getFullMetricName("storage_engine_query_latency_for_small_value")));
    bdbQueryLatencyForLargeValueSensor = registerSensor("storage_engine_query_latency_for_large_value",
        TehutiUtils.getPercentileStat(getName(), getFullMetricName("storage_engine_query_latency_for_large_value")));

    List<MeasurableStat> largeValueLookupStats = new ArrayList();

    /**
     * This is the max number of large values assembled per query. Useful to know if a given
     * store is currently exercising the large value feature on the read path, or not.
     *
     * For single gets, valid values would be 0 or 1.
     * For batch gets, valid values would be between 0 and {@link requestKeyCountSensor}'s Max.
     */
    largeValueLookupStats.add(new Max(0));

    /**
     * This represents the rate of requests which included at least one large value.
     */
    largeValueLookupStats.add(new OccurrenceRate());
    if (RequestType.MULTI_GET == requestType) {
      /**
       * This represents the average number of large values contained within a given batch get.
       *
       * N.B.: This is not useful for single get, as it will always be equal to Max, since we
       *       only record the metric when at least one large value look up occurred.
       */
      largeValueLookupStats.add(new Avg());

      /**
       * This represents the total rate of large values getting re-assembled, across all batch
       * gets.
       *
       * N.B.: This is only useful for batch gets. If we included this metric for single gets,
       *       it would always be equal to the OccurrenceRate, since single gets that include
       *       large value will only ever contain exactly 1 large value.
       */
      largeValueLookupStats.add(new Rate());
    }
    multiChunkLargeValueCountSensor = registerSensor("storage_engine_large_value_lookup",
        largeValueLookupStats.toArray(new MeasurableStat[largeValueLookupStats.size()]));

    Rate requestKeyCount = new OccurrenceRate();
    Rate successRequestKeyCount = new OccurrenceRate();
    requestKeyCountSensor = registerSensor("request_key_count", requestKeyCount, new Avg(), new Max());
    successRequestKeyCountSensor = registerSensor("success_request_key_count", successRequestKeyCount,
        new Avg(), new Max());
    successRequestKeyRatioSensor = registerSensor("success_request_key_ratio",
        new TehutiUtils.SimpleRatioStat(successRequestKeyCount, requestKeyCount));
  }

  public void recordSuccessRequest() {
    successRequestSensor.record();
  }

  public void recordErrorRequest() {
    errorRequestSensor.record();
  }

  public void recordSuccessRequestLatency(double latency) {
    successRequestLatencySensor.record(latency);
  }

  public void recordErrorRequestLatency(double latency) {
    errorRequestLatencySensor.record(latency);
  }

  public void recordBdbQueryLatency(double latency, boolean assembledMultiChunkLargeValue) {
    bdbQueryLatencySensor.record(latency);
    if (assembledMultiChunkLargeValue) {
      bdbQueryLatencyForLargeValueSensor.record(latency);
    } else {
      bdbQueryLatencyForSmallValueSensor.record(latency);
    }
  }

  public void recordRequestKeyCount(int keyCount) {
    requestKeyCountSensor.record(keyCount);
  }

  public void recordSuccessRequestKeyCount(int successKeyCount) {
    successRequestKeyCountSensor.record(successKeyCount);
  }

  public void recordMultiChunkLargeValueCount(int multiChunkLargeValueCount) {
    multiChunkLargeValueCountSensor.record(multiChunkLargeValueCount);
  }
}
