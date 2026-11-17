package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

public class TestLinkCongestionTable {
	private static final double DELTA = 1e-9;
	private static final Id<Link> A = Id.createLinkId("a");

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void looksUpBinsAndDefaultsToUncongested() {
		var table = new LinkCongestionTable.Builder() //
				.add(A, 3600, 7200, 1.4) //
				.add(A, 0, 3600, 1.1) //
				.add(A, 10800, 14400, 2.0) //
				.build();

		assertEquals(1.1, table.getCongestionRatio(A, 0), DELTA);
		assertEquals(1.1, table.getCongestionRatio(A, 3599), DELTA);
		assertEquals(1.4, table.getCongestionRatio(A, 3600), DELTA);
		assertEquals(1.0, table.getCongestionRatio(A, 9000), DELTA); // gap between bins
		assertEquals(2.0, table.getCongestionRatio(A, 14399), DELTA);
		assertEquals(1.0, table.getCongestionRatio(A, 14400), DELTA); // after last bin
		assertEquals(1.0, table.getCongestionRatio(A, -1), DELTA); // before first bin
		assertEquals(1.0, table.getCongestionRatio(Id.createLinkId("unknown"), 3600), DELTA);
	}

	@Test
	public void rejectsOverlappingAndInvalidBins() {
		var overlapping = new LinkCongestionTable.Builder().add(A, 0, 3600, 1.2).add(A, 1800, 5400, 1.3);
		assertThrows(IllegalStateException.class, overlapping::build);
		assertThrows(IllegalArgumentException.class, () -> new LinkCongestionTable.Builder().add(A, 10, 10, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new LinkCongestionTable.Builder().add(A, 0, 10, -1.0));
	}

	@Test
	public void readsTeammateFormatWithQuotesAndExtraColumns() throws IOException {
		Path file = folder.getRoot().toPath().resolve("congestion.csv");
		Files.writeString(file,
				"\"link_id\",\"time_bin_start_s\",\"time_bin_end_s\",\"congestion_ratio_per_link\",\"extra\"\n"
						+ "\"a\",25200,28800,1.63,x\n" //
						+ "\n" //
						+ "\"b\",0,3600,1.0,y\n");

		var table = LinkCongestionTable.read(file.toString());
		assertEquals(2, table.getNumberOfLinks());
		assertEquals(1.63, table.getCongestionRatio(A, 27000), DELTA);
	}

	@Test
	public void readsGzippedSemicolonCsvWithAnyColumnOrder() throws IOException {
		Path file = folder.getRoot().toPath().resolve("congestion.csv.gz");

		try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
				StandardCharsets.UTF_8)) {
			writer.write("congestion_ratio_per_link;link_id;time_bin_end_s;time_bin_start_s\n1.5;a;7200;3600\n");
		}

		var table = LinkCongestionTable.read(file.toString());
		assertEquals(1.5, table.getCongestionRatio(A, 5000), DELTA);
	}

	@Test
	public void failsOnMissingColumnOrBadValue() throws IOException {
		Path missing = folder.getRoot().toPath().resolve("missing.csv");
		Files.writeString(missing, "link_id,time_bin_start_s,time_bin_end_s\na,0,3600\n");
		assertThrows(IllegalStateException.class, () -> LinkCongestionTable.read(missing.toString()));

		Path bad = folder.getRoot().toPath().resolve("bad.csv");
		Files.writeString(bad, "link_id,time_bin_start_s,time_bin_end_s,congestion_ratio_per_link\na,0,3600,high\n");
		assertThrows(IllegalStateException.class, () -> LinkCongestionTable.read(bad.toString()));
	}
}
