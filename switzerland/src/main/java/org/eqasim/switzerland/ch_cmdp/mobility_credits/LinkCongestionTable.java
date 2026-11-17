package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.io.IOUtils;

/**
 * Baseline congestion per link and time bin, used for fixed congestion
 * pricing (option B).
 * <p>
 * Supports CSV files with either seconds (`time_bin_start_s`) or hours
 * (`bin_start_hour`),
 * and TTI or congestion ratio columns (`tti`, `congestion_ratio_per_link`).
 */
public class LinkCongestionTable {
	public static final double UNCONGESTED = 1.0;

	static final String LINK_ID = "link_id";

	private final Map<Id<Link>, Bins> bins;

	private LinkCongestionTable(Map<Id<Link>, Bins> bins) {
		this.bins = bins;
	}

	/**
	 * Returns the congestion ratio of the link at the given time, or 1.0 if there
	 * is no entry.
	 */
	public double getCongestionRatio(Id<Link> linkId, double time_s) {
		Bins linkBins = bins.get(linkId);
		return linkBins == null ? UNCONGESTED : linkBins.get(time_s);
	}

	public int getNumberOfLinks() {
		return bins.size();
	}

	public static LinkCongestionTable empty() {
		return new LinkCongestionTable(Map.of());
	}

	public static LinkCongestionTable read(String path) {
		Builder builder = new Builder();

		try (BufferedReader reader = IOUtils.getBufferedReader(path)) {
			String header = reader.readLine();

			if (header == null) {
				throw new IllegalStateException("Empty congestion file: " + path);
			}

			String delimiter = header.contains(";") ? ";" : ",";
			List<String> columns = Arrays.stream(header.split(delimiter, -1))
					.map(LinkCongestionTable::unquote)
					.toList();

			int linkIndex = requireColumn(columns, LINK_ID, path);

			// Determine start time column & whether it is in hours or seconds
			int startIndex = -1;
			boolean isStartInHours = false;
			if (columns.contains("time_bin_start_s")) {
				startIndex = columns.indexOf("time_bin_start_s");
			} else if (columns.contains("bin_start_hour")) {
				startIndex = columns.indexOf("bin_start_hour");
				isStartInHours = true;
			} else {
				throw new IllegalStateException(
						"Missing start time column ('time_bin_start_s' or 'bin_start_hour') in " + path);
			}

			// Determine end time column if available
			int endIndex = -1;
			boolean isEndInHours = false;
			if (columns.contains("time_bin_end_s")) {
				endIndex = columns.indexOf("time_bin_end_s");
			} else if (columns.contains("bin_end_hour")) {
				endIndex = columns.indexOf("bin_end_hour");
				isEndInHours = true;
			}

			// Determine ratio/TTI column
			int ratioIndex = -1;
			if (columns.contains("tti")) {
				ratioIndex = columns.indexOf("tti");
			} else if (columns.contains("congestion_ratio_per_link")) {
				ratioIndex = columns.indexOf("congestion_ratio_per_link");
			} else {
				throw new IllegalStateException(
						"Missing TTI / congestion ratio column ('tti' or 'congestion_ratio_per_link') in " + path);
			}

			String line;
			int lineNumber = 1;

			while ((line = reader.readLine()) != null) {
				lineNumber++;

				if (line.isBlank()) {
					continue;
				}

				String[] values = line.split(delimiter, -1);

				if (values.length < columns.size()) {
					throw new IllegalStateException(
							String.format("Line %d of %s has %d values, expected %d", lineNumber, path, values.length,
									columns.size()));
				}

				try {
					double rawStart = Double.parseDouble(unquote(values[startIndex]));
					double start_s = isStartInHours ? rawStart * 3600.0 : rawStart;

					double end_s;
					if (endIndex >= 0) {
						double rawEnd = Double.parseDouble(unquote(values[endIndex]));
						end_s = isEndInHours ? rawEnd * 3600.0 : rawEnd;
					} else {
						// Default 15-minute bin duration (900 seconds) if end time column is absent
						end_s = start_s + 900.0;
					}

					double congestionRatio = Double.parseDouble(unquote(values[ratioIndex]));

					builder.add(Id.createLinkId(unquote(values[linkIndex])), start_s, end_s, congestionRatio);
				} catch (RuntimeException e) {
					throw new IllegalStateException(
							String.format("Invalid line %d of %s: %s", lineNumber, path, e.getMessage()), e);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return builder.build();
	}

	private static int requireColumn(List<String> columns, String column, String path) {
		int index = columns.indexOf(column);

		if (index < 0) {
			throw new IllegalStateException(
					String.format("Column '%s' missing in %s (found %s)", column, path, columns));
		}

		return index;
	}

	private static String unquote(String value) {
		String trimmed = value.trim();

		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
		}

		return trimmed;
	}

	public static class Builder {
		private final Map<Id<Link>, List<double[]>> entries = new HashMap<>();

		public Builder add(Id<Link> linkId, double start_s, double end_s, double congestionRatio) {
			if (!Double.isFinite(start_s) || !Double.isFinite(end_s) || !(start_s < end_s)) {
				throw new IllegalArgumentException(
						String.format("Invalid bin [%f, %f) for link %s", start_s, end_s, linkId));
			}

			if (!Double.isFinite(congestionRatio) || congestionRatio < 0.0) {
				throw new IllegalArgumentException(
						String.format("Invalid congestion ratio %f for link %s", congestionRatio, linkId));
			}

			entries.computeIfAbsent(linkId, id -> new ArrayList<>())
					.add(new double[] { start_s, end_s, congestionRatio });
			return this;
		}

		public LinkCongestionTable build() {
			Map<Id<Link>, Bins> bins = new HashMap<>();

			for (var entry : entries.entrySet()) {
				List<double[]> sorted = new ArrayList<>(entry.getValue());
				sorted.sort(Comparator.comparingDouble(e -> e[0]));

				double[] starts = new double[sorted.size()];
				double[] ends = new double[sorted.size()];
				double[] ratios = new double[sorted.size()];

				for (int i = 0; i < sorted.size(); i++) {
					starts[i] = sorted.get(i)[0];
					ends[i] = sorted.get(i)[1];
					ratios[i] = sorted.get(i)[2];

					if (i > 0 && starts[i] < ends[i - 1]) {
						throw new IllegalStateException(String.format(
								"Overlapping time bins for link %s at %f s", entry.getKey(), starts[i]));
					}
				}

				bins.put(entry.getKey(), new Bins(starts, ends, ratios));
			}

			return new LinkCongestionTable(bins);
		}
	}

	private record Bins(double[] starts, double[] ends, double[] ratios) {
		double get(double time_s) {
			int index = Arrays.binarySearch(starts, time_s);

			if (index < 0) {
				// insertion point - 1 = last bin starting before time_s
				index = -index - 2;
			}

			if (index >= 0 && time_s < ends[index]) {
				return ratios[index];
			}

			return UNCONGESTED;
		}
	}
}