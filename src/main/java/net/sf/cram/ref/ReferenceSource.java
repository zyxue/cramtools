/*******************************************************************************
 * Copyright 2013 EMBL-EBI
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package net.sf.cram.ref;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.sf.cram.common.Utils;
import net.sf.cram.io.ByteBufferUtils;
import net.sf.picard.PicardException;
import net.sf.picard.reference.FastaSequenceIndex;
import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.reference.ReferenceSequenceFileFactory;
import net.sf.picard.util.Log;
import net.sf.samtools.SAMSequenceRecord;

public class ReferenceSource {
	private static Log log = Log.getInstance(ReferenceSource.class);
	private ReferenceSequenceFile rsFile;
	private FastaSequenceIndex fastaSequenceIndex;
	private int downloadTriesBeforeFailing = 2;

	private Map<String, WeakReference<byte[]>> cacheW = new HashMap<String, WeakReference<byte[]>>();

	public ReferenceSource() {
	}

	public ReferenceSource(File file) {
		if (file != null) {
			rsFile = ReferenceSequenceFileFactory
					.getReferenceSequenceFile(file);

			File indexFile = new File(file.getAbsoluteFile() + ".fai");
			if (indexFile.exists())
				fastaSequenceIndex = new FastaSequenceIndex(indexFile);
		}
	}

	public ReferenceSource(ReferenceSequenceFile rsFile) {
		this.rsFile = rsFile;
	}

	public void clearCache() {
		cacheW.clear();
	}

	private byte[] findInCache(String name) {
		WeakReference<byte[]> r = cacheW.get(name);
		if (r != null) {
			byte[] bytes = r.get();
			if (bytes != null)
				return bytes;
		}
		return null;
	}

	public synchronized byte[] getReferenceBases(SAMSequenceRecord record,
			boolean tryNameVariants) {
		{ // check cache by sequence name:
			String name = record.getSequenceName();
			byte[] bases = findInCache(name);
			if (bases != null)
				return bases;
		}

		String md5 = record.getAttribute(SAMSequenceRecord.MD5_TAG);
		{ // check cache by md5:
			if (md5 != null) {
				byte[] bases = findInCache(md5);
				if (bases != null)
					return bases;
				bases = findInCache(md5.toLowerCase());
				if (bases != null)
					return bases;
				bases = findInCache(md5.toUpperCase());
				if (bases != null)
					return bases;
			}
		}

		byte[] bases;

		{ // try to fetch sequence by name:
			bases = findBasesByName(record.getSequenceName(), tryNameVariants);
			if (bases != null) {
				Utils.upperCase(bases);
				cacheW.put(record.getSequenceName(), new WeakReference<byte[]>(
						bases));
				return bases;
			}
		}

		{ // try to fetch sequence by md5:
			if (md5 != null)
				try {
					bases = findBasesByMD5(md5.toLowerCase());
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			if (bases != null) {
				Utils.upperCase(bases);
				cacheW.put(md5, new WeakReference<byte[]>(bases));
				return bases;
			}
		}

		// sequence not found, give up:
		return null;
	}

	protected byte[] findBasesByName(String name, boolean tryVariants) {
		if (rsFile == null || !rsFile.isIndexed())
			return null;

		ReferenceSequence sequence = null;
		if (fastaSequenceIndex != null)
			if (fastaSequenceIndex.hasIndexEntry(name))
				sequence = rsFile.getSequence(name);
			else
				sequence = null;

		if (sequence != null)
			return sequence.getBases();

		if (tryVariants) {
			for (String variant : getVariants(name)) {
				try {
					sequence = rsFile.getSequence(variant);
				} catch (PicardException e) {
					log.info("Sequence not found: " + variant);
				}
				if (sequence != null)
					return sequence.getBases();
			}
		}
		return null;
	}

	protected byte[] findBasesByMD5(String md5) throws MalformedURLException,
			IOException {
		String url = String.format("http://www.ebi.ac.uk/ena/cram/md5/%s", md5);

		for (int i = 0; i < downloadTriesBeforeFailing; i++) {
			InputStream is = new URL(url).openStream();
			if (is == null)
				return null;

			log.info("Downloading reference sequence: " + url);
			byte[] data = ByteBufferUtils.readFully(is);
			log.info("Downloaded " + data.length + " bytes for md5 " + md5);
			is.close();

			try {
				String downloadedMD5 = Utils.calculateMD5String(data);
				if (md5.equals(downloadedMD5)) {
					return data;
				} else {
					String message = String
							.format("Downloaded sequence is corrupt: requested md5=%s, received md5=%s",
									md5, downloadedMD5);
					log.error(message);
				}
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}
		throw new RuntimeException("Giving up on downloading sequence for md5 "
				+ md5);
	}

	private static final Pattern chrPattern = Pattern.compile("chr.*",
			Pattern.CASE_INSENSITIVE);

	protected List<String> getVariants(String name) {
		List<String> variants = new ArrayList<String>();

		if (name.equals("M"))
			variants.add("MT");

		if (name.equals("MT"))
			variants.add("M");

		boolean chrPatternMatch = chrPattern.matcher(name).matches();
		if (chrPatternMatch)
			variants.add(name.substring(3));
		else
			variants.add("chr" + name);

		if ("chrM".equals(name)) {
			// chrM case:
			variants.add("MT");
		}
		return variants;
	}

	public int getDownloadTriesBeforeFailing() {
		return downloadTriesBeforeFailing;
	}

	public void setDownloadTriesBeforeFailing(int downloadTriesBeforeFailing) {
		this.downloadTriesBeforeFailing = downloadTriesBeforeFailing;
	}
}
