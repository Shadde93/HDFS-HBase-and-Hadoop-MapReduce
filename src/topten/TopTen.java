package topten;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.mapreduce.TableReducer;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;

public class TopTen {
  // This helper function parses the stackoverflow into a Map for us.
  public static Map<String, String> transformXmlToMap(String xml) {
    Map<String, String> map = new HashMap<String, String>();
    try {
      String[] tokens = xml.trim().substring(5, xml.trim().length() - 3).split("\"");
      for (int i = 0; i < tokens.length - 1; i += 2) {
        String key = tokens[i].trim();
        String val = tokens[i + 1];
        map.put(key.substring(0, key.length() - 1), val);
      }
    } catch (StringIndexOutOfBoundsException e) {
      System.err.println(xml);
    }

    return map;
  }

  public static class TopTenMapper extends Mapper<Object, Text, NullWritable, Text> {
    // Stores a map of user reputation to the record
    TreeMap<Integer, Text> repToRecordMap = new TreeMap<Integer, Text>();

    public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
      Map<String, String> row_entry = transformXmlToMap(value.toString());
      String id = row_entry.get("Id");

      if(id != null && id != "-1"){
        Integer rep = Integer.parseInt(row_entry.get("Reputation"));
        repToRecordMap.put(rep, new Text(value));
      }
    }

    protected void cleanup(Context context) throws IOException, InterruptedException {
      // Output our ten records to the reducers with a null key
      int i = 0; int N = 10;
      for (Map.Entry<Integer, Text>  entry : repToRecordMap.descendingMap().entrySet()) {
        if (i++ < N) {
          context.write(NullWritable.get(), entry.getValue());
        }else{
          break;
        }
      }
    }
  }

  public static class TopTenReducer extends TableReducer<NullWritable, Text, NullWritable> {
    // Stores a map of user reputation to the record
    private TreeMap<Integer, Text> repToRecordMap = new TreeMap<Integer, Text>();

    public void reduce(NullWritable key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
      for(Text value: values){
        Map<String, String> row_entry = transformXmlToMap(value.toString());
        Integer rep = Integer.parseInt(row_entry.get("Reputation"));
        repToRecordMap.put(rep, new Text(value));
      }

      int i = 0; int N = 10;
      for (Map.Entry<Integer, Text> entry : repToRecordMap.descendingMap().entrySet()) {
        if (i++ < N) {
          Map<String, String> row_entry = transformXmlToMap(entry.getValue().toString());

          Put insHBase = new Put(Bytes.toBytes(row_entry.get("Id") ));

          // insert sum value to hbase
          insHBase.addColumn(Bytes.toBytes("info"), Bytes.toBytes("id"), Bytes.toBytes(row_entry.get("Id") ));
          insHBase.addColumn(Bytes.toBytes("info"), Bytes.toBytes("rep"), Bytes.toBytes(row_entry.get("Reputation") ));

          // write data to Hbase table
          context.write(NullWritable.get(), insHBase);
        }else{
          break;
        }
      }

    }

  }

  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();

    // define scan and define column families to scan
    Job job = Job.getInstance(conf);
    job.setJarByClass(TopTen.class);

    job.setMapperClass(TopTenMapper.class);
    job.setMapOutputKeyClass(NullWritable.class);
    job.setMapOutputValueClass(Text.class);

    FileInputFormat.addInputPath(job, new Path(args[0]));

    // define output table
    TableMapReduceUtil.initTableReducerJob("topten", TopTenReducer.class, job);
    job.setNumReduceTasks(1);

    job.waitForCompletion(true);
  }
}
