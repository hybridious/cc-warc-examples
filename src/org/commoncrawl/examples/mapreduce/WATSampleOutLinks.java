package org.commoncrawl.examples.mapreduce;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveRecord;
import org.commoncrawl.warc.WARCFileInputFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Extract and randomly sample outlinks (links to pages, not image and media links) from WAT files.
 */
public class WATSampleOutLinks extends Configured implements Tool {

	private static final Logger LOG = Logger.getLogger(WATSampleOutLinks.class);

	protected static enum COUNTER {
		RECORDS,
		RESPONSE_RECORDS,
		RECORDS_NON_HTML,
		EXCEPTIONS,
		EXCEPTIONS_JSON,
		EXCEPTIONS_URL_MALFORMED,
		LINKS_PAGE_ACCEPTED,
		LINKS_TOTAL,
		LINKS_MEDIA_SKIPPED,
		LINKS_PAGE_UNIQ,
		LINKS_PAGE_UNIQ_ACCEPTED,
		LINKS_PAGE_UNIQ_SKIPPED_MAX_PER_PAGE,
		LINKS_RANDOM_SKIP,
		LINKS_RANDOM_SAMPLED,
	}

	protected static class OutLinkMapper extends Mapper<Text, ArchiveReader, Text, LongWritable> {
		private Text outKey = new Text();
		private LongWritable outVal = new LongWritable(1);
		int maxOutlinksPerPage = 80;

		@Override
		public void setup(Context context) {
			maxOutlinksPerPage = context.getConfiguration().getInt("wat.outlinks.max.per.page", 80);
		}

		@Override
		public void map(Text key, ArchiveReader value, Context context) throws IOException {
			for (ArchiveRecord r : value) {
				// Skip any records that are not JSON
				if (!r.getHeader().getMimetype().equals("application/json")) {
					continue;
				}
				try {
					context.getCounter(COUNTER.RECORDS).increment(1);
					// Convenience function that reads the full message into a raw byte array
					byte[] rawData = IOUtils.toByteArray(r, r.available());
					String content = new String(rawData);
					try {
						JSONObject json = new JSONObject(content);
						JSONObject warcHeader = json.getJSONObject("Envelope").getJSONObject("WARC-Header-Metadata");
						String warcType = warcHeader.getString("WARC-Type");
						if (!warcType.equals("response")) {
							continue;
						}
						context.getCounter(COUNTER.RESPONSE_RECORDS).increment(1);
						String base = warcHeader.getString("WARC-Target-URI");
						URL baseUrl = new URL(base);
						JSONObject responseMetaData = json.getJSONObject("Envelope")
								.getJSONObject("Payload-Metadata")
								.getJSONObject("HTTP-Response-Metadata");
						if (!responseMetaData.has("HTML-Metadata")) {
							context.getCounter(COUNTER.RECORDS_NON_HTML).increment(1);
							continue;
						}
						Set<String> outLinks = new HashSet<>();
						JSONObject htmlMetaData = responseMetaData.getJSONObject("HTML-Metadata");
						if (htmlMetaData.has("Head")) {
							JSONObject head = htmlMetaData.getJSONObject("Head");
							if (head.has("Base")) {
								base = head.getString("Base");
								baseUrl = new URL(baseUrl, base);
							}
							if (head.has("Link")) {
								// <link ...>
								addOutLinks(context, outLinks, baseUrl, head.getJSONArray("Link"));
							}
							if (head.has("Metas")) {
								JSONArray metas = head.getJSONArray("Metas");
								for (int i = 0, l = metas.length(); i < l; i++) {
									JSONObject meta = metas.getJSONObject(i);
									if (meta.has("property") && meta.getString("property").equals("og:url")
											&& meta.has("content")) {
										URL url = new URL(baseUrl, meta.getString("content"));
										context.getCounter(COUNTER.LINKS_TOTAL).increment(1);
										outLinks.add(url.toString());
									}
								}
							}
						}
						if (htmlMetaData.has("Links")) {
							JSONArray links = htmlMetaData.getJSONArray("Links");
							addOutLinks(context, outLinks, baseUrl, links);
						}
						context.getCounter(COUNTER.LINKS_PAGE_UNIQ).increment(outLinks.size());
						int n = 0;
						for (String url : outLinks) {
							n++;
							outKey.set(url.toString());
							context.write(outKey, outVal);
							if (n > maxOutlinksPerPage) {
								context.getCounter(COUNTER.LINKS_PAGE_UNIQ_SKIPPED_MAX_PER_PAGE)
										.increment(outLinks.size() - n);
								break;
							}
						}
						context.getCounter(COUNTER.LINKS_PAGE_UNIQ_ACCEPTED).increment(n);
					} catch (JSONException ex) {
						context.getCounter(COUNTER.EXCEPTIONS_JSON).increment(1);
						LOG.error("Caught Exception", ex);
					} catch (MalformedURLException ex) {
						context.getCounter(COUNTER.EXCEPTIONS_URL_MALFORMED).increment(1);
					} catch (Exception ex) {
						context.getCounter(COUNTER.EXCEPTIONS).increment(1);
						LOG.error("Caught Exception", ex);
					}
				}
				catch (Exception ex) {
					LOG.error("Caught Exception", ex);
					context.getCounter(COUNTER.EXCEPTIONS).increment(1);
				}
			}

		}

		private void addOutLinks(Context context, Collection<String> outLinks, URL baseUrl, JSONArray links)
				throws MalformedURLException, JSONException {
			context.getCounter(COUNTER.LINKS_TOTAL).increment(links.length());
			links:
			for (int i = 0, l = links.length(); i < l; i++) {
				JSONObject link = links.getJSONObject(i);
				if (link.has("url") && link.has("path")) {
					String path = link.getString("path");
					switch(path) {
					case "A@/href":
						break ;
					case "IMG@/src":
					case "FORM@/action":
					case "TD@/background":
					case "TABLE@/background":
					case "BODY@/background":
					case "AUDIO@/src":
					case "VIDEO@/src":
					case "TR@/background":
						// ignore images and media
						context.getCounter(COUNTER.LINKS_MEDIA_SKIPPED).increment(1);
						continue links;
					case "LINK@/href":
						if (link.has("rel")) {
							switch(link.getString("rel")) {
							case "canonical":
								break ;
							default:
								 // ignore rels not explicitly listed
								context.getCounter(COUNTER.LINKS_MEDIA_SKIPPED).increment(1);
								continue links;
							}
						}
						break;
					}
					context.getCounter(COUNTER.LINKS_PAGE_ACCEPTED).increment(1);
					URL url = new URL(baseUrl, link.getString("url"));
					outLinks.add(url.toString());
				}
			}
		}
	}


	protected static class OutLinkCombiner extends Reducer<Text, LongWritable, Text, LongWritable> {
		private LongWritable outVal = new LongWritable(1);

		@Override
		public void reduce(Text key, Iterable<LongWritable> values,
				Context context) throws IOException, InterruptedException {
			long sum = 0;
			for (LongWritable val : values) {
				sum += val.get();
			}
			outVal.set(sum);
			context.write(key, outVal);
		}

	}

	protected static class OutLinkReducer extends Reducer<Text, LongWritable, Text, LongWritable> {

		private double sampleProbability = .5;
		private LongWritable outVal = new LongWritable(1);

		@Override
		public void setup(Context context) {
			sampleProbability = context.getConfiguration().getDouble("sample.probability", .5);
			LOG.info("Outlink sample probability = " + sampleProbability);
			// invert sample probability for comparison with random number (0.0 <= random < 1.0)
			sampleProbability = (1.0 - sampleProbability);
		}

		@Override
		public void reduce(Text key, Iterable<LongWritable> values,
				Context context) throws IOException, InterruptedException {
			long sum = 0;
			for (LongWritable val : values) {
				sum += val.get();
			}
			if ((sum*Math.random()) >= sampleProbability) {
				// multiply random by number of times outlink URL has been observed
				outVal.set(sum);
				context.write(key, outVal);
				context.getCounter(COUNTER.LINKS_RANDOM_SAMPLED).increment(1);
			} else {
				context.getCounter(COUNTER.LINKS_RANDOM_SKIP).increment(1);
			}
		}

	}

	@Override
	public int run(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: WATSampleOutLinks [-Dproperty=value ...] <outputpath> <inputpath>...");
			System.err.println("  -Dwat.outlinks.sample.probability=<prob>");
			System.err.println("  \t\tprobability (0.0 < prob <= 1.0) to select an outlink");
			System.err.println("  -Dwat.outlinks.max.per.page=n");
			System.err.println("  \t\tmax. number of accepted outlinks per page");
			return -1;
		}
		Path outputPath = null;
		List<Path> inputPaths = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (outputPath == null) {
				outputPath = new Path(args[i]);
			} else {
				inputPaths.add(new Path(args[i]));
			}
		}
		return run(outputPath, inputPaths.toArray(new Path[inputPaths.size()]));
	}

	public int run(Path outputPath, Path[] inputPaths) throws IOException, ClassNotFoundException, InterruptedException {
		Configuration conf = getConf();

		Job job = Job.getInstance(conf);
		job.setJarByClass(WATSampleOutLinks.class);

		double sampleProbability = conf.getDouble("wat.outlinks.sample.probability", .5);

		for (int i = 0; i < inputPaths.length; i++) {
			LOG.info("Input path: " + inputPaths[i]);
			FileInputFormat.addInputPath(job, inputPaths[i]);
		}

		FileOutputFormat.setOutputPath(job, outputPath);
		LOG.info("Output path: " + outputPath);

		job.setInputFormatClass(WARCFileInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(LongWritable.class);

		job.setMapperClass(OutLinkMapper.class);
		job.setCombinerClass(OutLinkCombiner.class);
		if (sampleProbability >= 1.0) {
			LOG.info("Sample probablity >= 1.0: no random sampling, output all outlinks");
			job.setReducerClass(OutLinkCombiner.class);
		} else {
			LOG.info("Sampling outlinks with probability " + sampleProbability);
			job.setReducerClass(OutLinkReducer.class);
		}

		if (job.waitForCompletion(true)) {
			return 0;
		}
		return 1;
	}

	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new WATSampleOutLinks(), args);
		System.exit(res);
	}

}
