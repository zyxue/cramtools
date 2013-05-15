package net.sf.cram;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bouncycastle.util.Arrays;

import net.sf.cram.CramTools.LevelConverter;
import net.sf.cram.build.ContainerParser;
import net.sf.cram.build.Cram2BamRecordFactory;
import net.sf.cram.build.CramNormalizer;
import net.sf.cram.build.CramIO;
import net.sf.cram.common.Utils;
import net.sf.cram.index.CramIndex;
import net.sf.cram.index.CramIndex.Entry;
import net.sf.cram.io.CountingInputStream;
import net.sf.cram.ref.ReferenceSource;
import net.sf.cram.structure.Container;
import net.sf.cram.structure.CramHeader;
import net.sf.cram.structure.CramRecord;
import net.sf.cram.structure.Slice;
import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.picard.util.Log;
import net.sf.picard.util.Log.LogLevel;
import net.sf.samtools.BAMFileWriter;
import net.sf.samtools.BAMIndexFactory;
import net.sf.samtools.SAMFileWriter;
import net.sf.samtools.SAMFileWriterFactory;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceRecord;
import net.sf.samtools.SAMTextWriter;
import net.sf.samtools.util.SeekableFileStream;
import net.sf.samtools.util.SeekableStream;
import uk.ac.ebi.embl.ega_cipher.CipherInputStream_256;
import uk.ac.ebi.embl.ega_cipher.SeekableCipherStream_256;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.FileConverter;

public class Cram2Bam {
	private static Log log = Log.getInstance(Cram2Bam.class);

	private static void printUsage(JCommander jc) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		jc.usage(sb);

		System.out.println("Version "
				+ Cram2Bam.class.getPackage().getImplementationVersion());
		System.out.println(sb.toString());
	}

	public static void main(String[] args) throws IOException,
			IllegalArgumentException, IllegalAccessException {
		Params params = new Params();
		JCommander jc = new JCommander(params);
		try {
			jc.parse(args);
		} catch (Exception e) {
			System.out
					.println("Failed to parse parameteres, detailed message below: ");
			System.out.println(e.getMessage());
			System.out.println();
			System.out.println("See usage: -h");
			System.exit(1);
		}

		if (args.length == 0 || params.help) {
			printUsage(jc);
			System.exit(1);
		}

		Log.setGlobalLogLevel(params.logLevel);

		if (params.reference == null)
			log.warn("No reference file specified, remote access over internet may be used to download public sequences. ");
		ReferenceSource referenceSource = new ReferenceSource(params.reference);

		if (params.locations == null)
			params.locations = new ArrayList<String>();

		char[] pass = null;
		if (params.decrypt) {
			if (System.console() == null)
				throw new RuntimeException("Cannot access console.");
			pass = System.console().readPassword();
		}

		InputStream is;
		if (params.cramFile != null) {
			FileInputStream fis = new FileInputStream(params.cramFile);
			if (params.locations == null || params.locations.isEmpty())
				is = new BufferedInputStream(fis);
			else
				is = new SeekableFileStream(params.cramFile);
		} else
			is = System.in;

		if (params.decrypt) {
			CipherInputStream_256 cipherInputStream_256 = new CipherInputStream_256(
					is, pass, 128);
			is = cipherInputStream_256.getCipherInputStream();
			if (params.locations != null && !params.locations.isEmpty()) {
				is = new SeekableCipherStream_256(new SeekableFileStream(
						params.cramFile), pass, 1, 128);
			}
		}

		long offset = 0;
		CountingInputStream cis = new CountingInputStream(is);
		CramHeader cramHeader = CramIO.readCramHeader(cis);
		offset = cis.getCount();

		SAMFileWriterFactory samFileWriterFactory = new SAMFileWriterFactory();
		samFileWriterFactory.setAsyncOutputBufferSize(10000);
		samFileWriterFactory.setCreateIndex(false);
		samFileWriterFactory.setCreateMd5File(false);
		samFileWriterFactory.setUseAsyncIo(true);

		SAMFileWriter writer = createSAMFileWriter(params, cramHeader,
				samFileWriterFactory);

		Container c = null;
		AlignmentSliceQuery location = null;
		if (!params.locations.isEmpty() && params.cramFile != null
				&& is instanceof SeekableStream) {
			if (params.locations.size() > 1)
				throw new RuntimeException("Only one location is supported.");

			location = new AlignmentSliceQuery(params.locations.get(0));

			c = skipToContainer(params.cramFile, cramHeader,
					(SeekableStream) is, location);

			if (c == null) {
				log.error("Index file not found. ");
				return;
			}
		}

		long recordCount = 0;
		long readTime = 0;
		long parseTime = 0;
		long normTime = 0;
		long samTime = 0;
		long writeTime = 0;
		long time = 0;
		int containerCounter = 0;
		ArrayList<CramRecord> cramRecords = new ArrayList<CramRecord>(10000);

		CramNormalizer n = new CramNormalizer(cramHeader.samFileHeader,
				referenceSource);

		byte[] ref = null;
		int prevSeqId = -1;

		ContainerParser parser = new ContainerParser(cramHeader.samFileHeader);
		while (true) {
			time = System.nanoTime();
			c = CramIO.readContainer(is);
			if (c == null)
				break;
			readTime += System.nanoTime() - time;

			containerCounter++;

			// for random access check if the sequence is the one we are looking
			// for:
			if (location != null
					&& cramHeader.samFileHeader.getSequence(location.sequence)
							.getSequenceIndex() != c.sequenceId)
				break;

			if (params.countOnly && params.requiredFlags == 0
					&& params.filteringFlags == 0) {
				recordCount += c.nofRecords;
				continue;
			}

			try {
				time = System.nanoTime();
				cramRecords.clear();
				parser.getRecords(c, cramRecords);
				parseTime += System.nanoTime() - time;
			} catch (EOFException e) {
				throw e;
			}

			switch (c.sequenceId) {
			case SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX:
			case -2:
				ref = new byte[] {};
				break;

			default:
				if (prevSeqId < 0 || prevSeqId != c.sequenceId) {
					SAMSequenceRecord sequence = cramHeader.samFileHeader
							.getSequence(c.sequenceId);
					ref = referenceSource.getReferenceBases(sequence, true);
					prevSeqId = c.sequenceId;
				}
				break;
			}

			try {
				for (int i = 0; i < c.slices.length; i++) {
					Slice s = c.slices[i];
					if (s.sequenceId < 0)
						continue;
					if (!s.validateRefMD5(ref)) {
						String md5 = Utils.calculateMD5(
								ref,
								s.alignmentStart - 1,
								Math.min(s.alignmentSpan, ref.length
										- s.alignmentStart + 1));
						String sliceMD5 = String.format("%032x",
								new BigInteger(1, s.refMD5));
						if (!md5.equals(sliceMD5)) {
							log.error(String
									.format("MD5 mismatch in slice %d container %d, seq %d, start %d, span %d, slice md5 %s, calculated md5 %s.",
											i, containerCounter, s.sequenceId,
											s.alignmentStart, s.alignmentSpan,
											sliceMD5, md5));
						}
					}
				}
			} catch (NoSuchAlgorithmException e1) {
				throw new RuntimeException(e1);
			}

			long time1 = System.nanoTime();
			n.normalize(cramRecords, true, ref, c.alignmentStart,
					c.h.substitutionMatrix, c.h.AP_seriesDelta);
			long time2 = System.nanoTime();
			normTime += time2 - time1;

			Cram2BamRecordFactory c2sFactory = new Cram2BamRecordFactory(
					cramHeader.samFileHeader);

			long c2sTime = 0;
			long sWriteTime = 0;

			boolean enough = false;
			for (CramRecord r : cramRecords) {
				// check if the record ends before the query start:
				if (location != null && r.alignmentStart < location.start)
					continue;

				time = System.nanoTime();
				SAMRecord s = c2sFactory.create(r);

				if (params.requiredFlags != 0
						&& ((params.requiredFlags & s.getFlags()) == 0))
					continue;
				if (params.filteringFlags != 0
						&& ((params.filteringFlags & s.getFlags()) != 0))
					continue;
				if (params.countOnly) {
					recordCount++;
					continue;
				}

				if (ref != null)
					Utils.calculateMdAndNmTags(s, ref, params.calculateMdTag,
							params.calculateNmTag);
				c2sTime += System.nanoTime() - time;
				samTime += System.nanoTime() - time;

				time = System.nanoTime();
				writer.addAlignment(s);
				sWriteTime += System.nanoTime() - time;
				writeTime += System.nanoTime() - time;
				if (params.outputFile == null && System.out.checkError())
					break;

				// we got all the reads for random access:
				if (location != null && location.end < s.getAlignmentStart()) {
					enough = true;
					break;
				}
			}

			log.info(String
					.format("CONTAINER READ: io %dms, parse %dms, norm %dms, convert %dms, BAM write %dms",
							c.readTime / 1000000, c.parseTime / 1000000,
							(time2 - time1) / 1000000, c2sTime / 1000000,
							sWriteTime / 1000000));

			if (enough
					|| (params.outputFile == null && System.out.checkError()))
				break;
		}

		if (params.countOnly)
			System.out.println(recordCount);

		writer.close();

		log.warn(String
				.format("TIMES: io %ds, parse %ds, norm %ds, convert %ds, BAM write %ds",
						readTime / 1000000000, parseTime / 1000000000,
						normTime / 1000000000, samTime / 1000000000,
						writeTime / 1000000000));
	}

	private static Container skipToContainer(File cramFile,
			CramHeader cramHeader, SeekableStream cramFileInputStream,
			AlignmentSliceQuery location) throws IOException {
		Container c = null;

		{ // try crai:
			List<CramIndex.Entry> entries = getCraiEntries(cramFile,
					cramHeader, cramFileInputStream, location);
			if (entries != null) {
				try {
					Entry leftmost = CramIndex.getLeftmost(entries);
					cramFileInputStream.seek(leftmost.containerStartOffset);
					c = CramIO.readContainerHeader(cramFileInputStream);
					if (c == null)
						return null;
					if (c.alignmentStart + c.alignmentSpan > location.start) {
						cramFileInputStream.seek(leftmost.containerStartOffset);
						return c;
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}

		{ // try bai:
			long[] filePointers = getBaiFilePointers(cramFile, cramHeader,
					cramFileInputStream, location);
			if (filePointers.length == 0) {
				return null;
			}

			for (int i = 0; i < filePointers.length; i += 2) {
				long offset = filePointers[i] >>> 16;
				int sliceIndex = (int) ((filePointers[i] << 48) >>> 48);
				try {
					cramFileInputStream.seek(offset);
					c = CramIO.readContainerHeader(cramFileInputStream);
					if (c.alignmentStart + c.alignmentSpan > location.start) {
						cramFileInputStream.seek(offset);
						return c;
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}

		return c;
	}

	private static long[] getBaiFilePointers(File cramFile,
			CramHeader cramHeader, SeekableStream cramFileInputStream,
			AlignmentSliceQuery location) throws IOException {
		long[] filePointers = new long[0];

		File indexFile = new File(cramFile.getAbsolutePath() + ".bai");
		if (indexFile.exists())
			filePointers = BAMIndexFactory.SHARED_INSTANCE.getBAMIndexPointers(
					indexFile,
					cramHeader.samFileHeader.getSequenceDictionary(),
					location.sequence, location.start, location.end);
		return filePointers;
	}

	private static List<CramIndex.Entry> getCraiEntries(File cramFile,
			CramHeader cramHeader, SeekableStream cramFileInputStream,
			AlignmentSliceQuery location) throws IOException {
		File indexFile = new File(cramFile.getAbsolutePath() + ".crai");
		if (indexFile.exists()) {
			FileInputStream fis = new FileInputStream(indexFile);
			GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(
					fis));
			BufferedInputStream bis = new BufferedInputStream(gis);
			List<CramIndex.Entry> full = CramIndex.readIndex(gis);

			List<CramIndex.Entry> entries = new LinkedList<CramIndex.Entry>();
			SAMSequenceRecord sequence = cramHeader.samFileHeader
					.getSequence(location.sequence);
			if (sequence == null)
				throw new RuntimeException("Sequence not found: "
						+ location.sequence);

			entries.addAll(CramIndex.find(full, sequence.getSequenceIndex(),
					location.start, location.end - location.start));

			bis.close();

			return entries;
		}
		return null;
	}

	private static SAMFileWriter createSAMFileWriter(Params params,
			CramHeader cramHeader, SAMFileWriterFactory samFileWriterFactory)
			throws IOException {
		/*
		 * building sam writer, sometimes we have to go deeper to get to the
		 * required functionality:
		 */
		SAMFileWriter writer = null;
		if (params.outputFastq) {
			if (params.cramFile == null) {
				writer = new FastqSAMFileWriter(System.out, null,
						cramHeader.samFileHeader);
			} else {
				writer = new FastqSAMFileWriter(
						params.cramFile.getAbsolutePath(), false,
						cramHeader.samFileHeader);

			}
		} else if (params.outputFastqGz) {
			if (params.cramFile == null) {
				GZIPOutputStream gos = new GZIPOutputStream(System.out);
				PrintStream ps = new PrintStream(gos);
				writer = new FastqSAMFileWriter(ps, null,
						cramHeader.samFileHeader);
			} else {
				writer = new FastqSAMFileWriter(
						params.cramFile.getAbsolutePath(), true,
						cramHeader.samFileHeader);

			}
		} else if (params.outputFile == null) {
			OutputStream os = new BufferedOutputStream(System.out);
			if (params.outputBAM) {
				BAMFileWriter ret = new BAMFileWriter(os, null);
				ret.setSortOrder(cramHeader.samFileHeader.getSortOrder(), true);
				ret.setHeader(cramHeader.samFileHeader);
				writer = ret;
			} else {
				writer = Utils.createSAMTextWriter(samFileWriterFactory, os,
						cramHeader.samFileHeader, params.printSAMHeader);
			}
		} else {
			writer = samFileWriterFactory.makeSAMOrBAMWriter(
					cramHeader.samFileHeader, true, params.outputFile);
		}
		return writer;
	}

	@Parameters(commandDescription = "CRAM to BAM conversion. ")
	static class Params {
		@Parameter(names = { "-l", "--log-level" }, description = "Change log level: DEBUG, INFO, WARNING, ERROR.", converter = LevelConverter.class)
		LogLevel logLevel = LogLevel.ERROR;

		@Parameter(names = { "--input-cram-file", "-I" }, converter = FileConverter.class, description = "The path to the CRAM file to uncompress. Omit if standard input (pipe).")
		File cramFile;

		@Parameter(names = { "--reference-fasta-file", "-R" }, converter = FileConverter.class, description = "Path to the reference fasta file, it must be uncompressed and indexed (use 'samtools faidx' for example). ")
		File reference;

		@Parameter(names = { "--output-bam-file", "-O" }, converter = FileConverter.class, description = "The path to the output BAM file.")
		File outputFile;

		@Parameter(names = { "-b", "--output-bam-format" }, description = "Output in BAM format.")
		boolean outputBAM = false;

		@Parameter(names = { "-q", "--output-fastq-format" }, hidden = true, description = "Output in fastq format.")
		boolean outputFastq = false;

		@Parameter(names = { "-z", "--output-fastq-gz-format" }, hidden = true, description = "Output in gzipped fastq format.")
		boolean outputFastqGz = false;

		@Parameter(names = { "--print-sam-header" }, description = "Print SAM header when writing SAM format.")
		boolean printSAMHeader = false;

		@Parameter(names = { "-h", "--help" }, description = "Print help and quit")
		boolean help = false;

		@Parameter(names = { "--default-quality-score" }, description = "Use this quality score (decimal representation of ASCII symbol) as a default value when the original quality score was lost due to compression. Minimum is 33.")
		int defaultQS = '?';

		@Parameter(names = { "--calculate-md-tag" }, description = "Calculate MD tag.")
		boolean calculateMdTag = false;

		@Parameter(names = { "--calculate-nm-tag" }, description = "Calculate NM tag.")
		boolean calculateNmTag = false;

		@Parameter()
		List<String> locations;

		@Parameter(names = { "--decrypt" }, description = "Decrypt the file.")
		boolean decrypt = false;

		@Parameter(names = { "--count-only", "-c" }, description = "Count number of records.")
		boolean countOnly = false;

		@Parameter(names = { "--required-flags", "-f" }, description = "Required flags. ")
		int requiredFlags = 0;

		@Parameter(names = { "--filter-flags", "-F" }, description = "Filtering flags. ")
		int filteringFlags = 0;

	}

}
