package org.apache.flink.streaming.examples.wordcount;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;

public class WordCountJerry {

  public static void main(String[] args) {

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

    DataStream<Tuple2<String, Integer>> dataStream = env
            .socketTextStream("localhost", 9999)
            .flatMap(new Tokenizer())
            .groupBy(0)
            .sum(1);

    dataStream.print();

    try {
      env.execute("Socket Stream WordCount");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static final class Tokenizer implements FlatMapFunction<String, Tuple2<String, Integer>> {
    private static final long serialVersionUID = 1L;

    @Override
    public void flatMap(String value, Collector<Tuple2<String, Integer>> out)
            throws Exception {
      // normalize and split the line
      String[] tokens = value.toLowerCase().split("\\W+");

      // emit the pairs
      for (String token : tokens) {
        if (token.length() > 0) {
          out.collect(new Tuple2<String, Integer>(token, 1));
        }
      }
    }
  }
//  public static class Splitter implements FlatMapFunction<String, Tuple2<String, Integer>> {
//    @Override
//    public void flatMap(String sentence, Collector<Tuple2<String, Integer>> out) throws Exception {
//      for (String word: sentence.split(" ")) {
//        out.collect(new Tuple2<String, Integer>(word, 1));
//      }
//    }
//  }

}